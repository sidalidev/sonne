package dev.sidali.sonne

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur GATT du téléphone protégé (section « Serveur GATT » du protocole) : un service
 * primaire Sonne avec la caractéristique Commande (écriture avec réponse) et la caractéristique
 * État (notification). Pas d'appairage : la sécurité est celle des messages, exactement comme
 * sur le relais. Ouvert seulement en mode recherche.
 *
 * Un BluetoothGattServer voit TOUS les liens LE du téléphone (montre, écouteurs, liens des
 * autres apps). Une connexion n'est donc comptée qu'au premier accès au service Sonne
 * (abonnement à l'État ou écriture d'une Commande). Défenses contre un inconnu à portée :
 * - 4 connexions Sonne au plus ; à saturation, la plus ancienne sans commande valide est évincée ;
 * - 5 s de vie sans commande valide, 10 s après une commande valide ;
 * - 10 requêtes ATT par connexion, 2 écritures invalides, puis coupure ;
 * - authentification (v3) ou signature (v2), fraîcheur et nonce vérifiés AVANT la limite de
 *   20 commandes par minute (seules les commandes recevables comptent : ni des octets au
 *   hasard ni une commande captée et rejouée ne peuvent épuiser la limite) ;
 * - écritures de 1 à 512 octets pendant la transition, de 172 exactement après la coupure ;
 * - notifications réservées aux connexions qui ont écrit une commande valide.
 *
 * Les rappels arrivent sur un fil Binder : la réponse ATT part tout de suite, le reste
 * (suivi des connexions, traitement des commandes) passe sur le fil principal.
 * Les permissions (BLUETOOTH_CONNECT) sont vérifiées par l'appelant, VeilleService.
 *
 * @param commandeRecevable forme, authentification ou signature, fraîcheur et nonce inédit
 *   d'une commande (fil principal, peu coûteux, sans rien modifier).
 * @param surCommande appelé sur le fil principal avec le texte d'une commande recevable et
 *   l'adresse du client. Le traitement est celui des commandes du relais.
 * @param surPret résultat de l'ajout du service (asynchrone) : true quand le serveur est prêt.
 * @param surDeconnexion un client non appairé s'est déconnecté (la balise peut être à relancer).
 */
@SuppressLint("MissingPermission")
class ServeurGatt(
    context: Context,
    private val commandeRecevable: (texte: String) -> Boolean,
    private val surCommande: (texte: String, adresse: String) -> Unit,
    private val surPret: (pret: Boolean) -> Unit,
    private val surDeconnexion: () -> Unit,
) {

    private class Connexion(val appareil: BluetoothDevice, val debut: Long) {
        var fermeture: Runnable? = null
        var commandeValide = false
        var invalides = 0
        var abonne = false
        val file = ArrayDeque<ByteArray>()
        var enVol = false
    }

    private enum class Phase { FERME, AJOUT, PRET }

    private val contexte = context.applicationContext ?: context
    private val principal = Handler(Looper.getMainLooper())

    // Fil principal seulement.
    private val connexions = LinkedHashMap<String, Connexion>()
    private val coupes = HashMap<String, Long>() // adresse coupée par Sonne, vers l'instant de la coupure
    private var phase = Phase.FERME

    // Lus et écrits depuis les fils Binder.
    private val abonnes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val requetes = ConcurrentHashMap<String, AtomicInteger>()

    private val debit = LimiteDebit(Proximite.COMMANDES_PAR_MINUTE, 60_000L)

    @Volatile
    private var serveur: BluetoothGattServer? = null
    private var caracteristiqueEtat: BluetoothGattCharacteristic? = null

    /** Serveur ouvert (service en cours d'ajout ou prêt). */
    val ouvert: Boolean get() = serveur != null

    /** Service Sonne publié : la balise peut émettre. */
    val pret: Boolean get() = serveur != null && phase == Phase.PRET

    /**
     * Ouvre le serveur et demande l'ajout du service Sonne. false si le Bluetooth est
     * indisponible ou la demande refusée tout de suite (juste après STATE_ON, la pile GATT
     * n'est parfois pas prête) : l'appelant réessaie. Sinon le résultat arrive par surPret.
     */
    fun ouvrir(): Boolean {
        if (serveur != null) return true
        val gestionnaire = contexte.getSystemService(BluetoothManager::class.java) ?: return false
        val s = try {
            gestionnaire.openGattServer(contexte, rappels)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the GATT server: ${e.message}")
            null
        } ?: return false

        val commande = BluetoothGattCharacteristic(
            Proximite.UUID_COMMANDE,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val etat = BluetoothGattCharacteristic(
            Proximite.UUID_ETAT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        etat.addDescriptor(
            BluetoothGattDescriptor(
                Proximite.UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val service = BluetoothGattService(Proximite.UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(commande)
        service.addCharacteristic(etat)
        serveur = s
        caracteristiqueEtat = etat
        phase = Phase.AJOUT
        // Asynchrone : true veut seulement dire « demande en file ». Le résultat arrive dans onServiceAdded.
        val demande = try {
            s.addService(service)
        } catch (e: Exception) {
            false
        }
        if (!demande) {
            Log.w(TAG, "GATT service add refused")
            fermer()
            return false
        }
        return true
    }

    /** Ferme le serveur et coupe toutes les connexions Sonne. */
    fun fermer() {
        val s = serveur ?: return
        serveur = null
        caracteristiqueEtat = null
        phase = Phase.FERME
        principal.removeCallbacksAndMessages(null)
        try {
            for (c in connexions.values) s.cancelConnection(c.appareil)
            s.close()
        } catch (_: Exception) {
            // Bluetooth déjà éteint.
        }
        connexions.clear()
        coupes.clear()
        abonnes.clear()
        mtus.clear()
        requetes.clear()
    }

    /**
     * Notifie un état aux connexions abonnées qui ont écrit une commande valide pendant la
     * connexion en cours : seulement à `adresse` si elle est donnée, sinon à toutes. Un inconnu
     * abonné ne reçoit rien (pas même « vient de se mettre à sonner »).
     * `construire` reçoit la place disponible (MTU - 3) et renvoie le message, ou null.
     */
    fun notifier(adresse: String?, construire: (max: Int) -> ByteArray?) {
        if (!pret) return
        val cibles = connexions.values.filter {
            it.abonne && it.commandeValide && (adresse == null || it.appareil.address == adresse)
        }
        for (c in cibles) {
            val max = (mtus[c.appareil.address] ?: MTU_DEFAUT) - 3
            val valeur = construire(max) ?: continue
            if (valeur.size > max) {
                Log.w(TAG, "Status too long for the connection MTU (${valeur.size} > $max)")
                continue
            }
            c.file.addLast(valeur)
            pomper(c)
        }
    }

    /** Envoie la notification suivante de la file, si aucune n'est en vol (une à la fois). */
    private fun pomper(c: Connexion) {
        val s = serveur ?: return
        val car = caracteristiqueEtat ?: return
        while (!c.enVol && c.file.isNotEmpty()) {
            val valeur = c.file.removeFirst()
            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val code = s.notifyCharacteristicChanged(c.appareil, car, false, valeur)
                    if (code != BluetoothStatusCodes.SUCCESS) Log.w(TAG, "Notification refused (code $code)")
                    code == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    car.value = valeur
                    @Suppress("DEPRECATION")
                    val envoyee = s.notifyCharacteristicChanged(c.appareil, car, false)
                    if (!envoyee) Log.w(TAG, "Notification refused")
                    envoyee
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot notify: ${e.message}")
                false
            }
            // Partie : la suivante attend onNotificationSent. Refusée : perdue (journalisée), on passe à la suivante.
            if (ok) c.enVol = true
        }
    }

    // -----------------------------------------------------------------
    // Suivi des connexions (fil principal)
    // -----------------------------------------------------------------

    /**
     * La connexion Sonne de cet appareil, admise à son premier accès au service. À saturation,
     * la plus ancienne sans commande valide est évincée ; si toutes en ont envoyé une, le
     * nouveau venu est refusé. null : refusé, ou déjà coupé par Sonne.
     */
    private fun admettre(appareil: BluetoothDevice): Connexion? {
        val adresse = appareil.address
        connexions[adresse]?.let { return it }
        val maintenant = SystemClock.elapsedRealtime()
        coupes.entries.removeIf { maintenant - it.value > OUBLI_COUPURE_MS }
        if (adresse in coupes) return null
        if (connexions.size >= Proximite.CONNEXIONS_MAX) {
            val victime = aEvincer(connexions.values, { it.debut }, { it.commandeValide })
            if (victime == null) {
                Log.i(TAG, "Too many GATT connections with a command in progress: refused")
                couper(appareil)
                return null
            }
            Log.i(TAG, "Too many GATT connections: evicting the oldest one without a command")
            couper(victime.appareil)
        }
        val c = Connexion(appareil, maintenant)
        connexions[adresse] = c
        armer(c, Proximite.DUREE_SANS_COMMANDE_MS)
        return c
    }

    /** Une connexion ne garde pas sa place plus de `ms`. */
    private fun armer(c: Connexion, ms: Long) {
        c.fermeture?.let { principal.removeCallbacks(it) }
        c.fermeture = Runnable { couper(c.appareil) }.also { principal.postDelayed(it, ms) }
    }

    /**
     * Coupe une connexion et libère sa place tout de suite, sans attendre un STATE_DISCONNECTED
     * qui peut ne jamais venir (lien tenu par une autre app).
     */
    private fun couper(appareil: BluetoothDevice) {
        val adresse = appareil.address
        try {
            serveur?.cancelConnection(appareil)
        } catch (_: Exception) {
        }
        connexions.remove(adresse)?.fermeture?.let { principal.removeCallbacks(it) }
        abonnes.remove(adresse)
        coupes[adresse] = SystemClock.elapsedRealtime()
    }

    /** Une écriture mal formée, illisible ou mal signée : 2 au plus par connexion, puis coupure. */
    private fun invalide(appareil: BluetoothDevice) {
        val c = admettre(appareil) ?: return
        c.invalides++
        if (c.invalides >= Proximite.ECRITURES_INVALIDES_MAX) {
            Log.i(TAG, "Repeated invalid writes: disconnected")
            couper(appareil)
        }
    }

    // -----------------------------------------------------------------
    // Rappels (fils Binder)
    // -----------------------------------------------------------------

    /**
     * Budget de requêtes ATT par connexion : chaque requête réveille le processeur et l'app.
     * Au-delà, plus de réponse et la connexion est coupée.
     */
    private fun dansLeBudget(appareil: BluetoothDevice): Boolean {
        val n = requetes.getOrPut(appareil.address) { AtomicInteger() }.incrementAndGet()
        if (n <= Proximite.BUDGET_REQUETES) return true
        if (n == Proximite.BUDGET_REQUETES + 1) {
            Log.i(TAG, "Too many ATT requests: disconnected")
            principal.post { if (serveur != null) couper(appareil) }
        }
        return false
    }

    private fun repondre(appareil: BluetoothDevice, requete: Int, statut: Int, valeur: ByteArray?) {
        try {
            serveur?.sendResponse(appareil, requete, statut, 0, valeur)
        } catch (_: Exception) {
        }
    }

    private val rappels = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            principal.post {
                if (serveur == null || phase != Phase.AJOUT || service.uuid != Proximite.UUID_SERVICE) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    phase = Phase.PRET
                    Log.i(TAG, "GATT server ready")
                    surPret(true)
                } else {
                    Log.w(TAG, "GATT service refused by the stack (code $status)")
                    fermer()
                    surPret(false)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Rien à compter à la connexion : seul le premier accès au service Sonne compte.
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            val adresse = device.address
            requetes.remove(adresse)
            mtus.remove(adresse)
            abonnes.remove(adresse)
            val appaire = try {
                device.bondState == BluetoothDevice.BOND_BONDED
            } catch (_: Exception) {
                false
            }
            principal.post {
                if (serveur == null) return@post
                connexions.remove(adresse)?.fermeture?.let { principal.removeCallbacks(it) }
                coupes.remove(adresse)
                // Montre, écouteurs (appairés) : rien à voir avec la balise.
                if (!appaire) surDeconnexion()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            principal.post {
                val c = connexions[device.address] ?: return@post
                c.enVol = false
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Notification not delivered (code $status)")
                pomper(c)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (!dansLeBudget(device)) return
            val statut = if (characteristic.uuid != Proximite.UUID_COMMANDE) {
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            } else {
                Proximite.statutEcriture(preparedWrite, offset, value?.size ?: 0)
            }
            // La réponse part avant le traitement et ne dit rien de la validité de la commande.
            if (responseNeeded) repondre(device, requestId, statut, null)
            if (statut != Proximite.GATT_SUCCESS || value == null) {
                principal.post { if (serveur != null) invalide(device) }
                return
            }
            val texte = String(value, Charsets.UTF_8)
            // En Doze, la pile ne tient le processeur éveillé qu'un instant : un verrou court
            // couvre le passage sur le fil principal, jusqu'à la fin du traitement.
            val verrou = verrouCourt()
            principal.post {
                try {
                    if (serveur == null) return@post
                    val c = admettre(device) ?: return@post
                    if (!commandeRecevable(texte)) {
                        invalide(device)
                        return@post
                    }
                    // Seules les commandes recevables comptent dans la limite globale.
                    if (!debit.autoriser(SystemClock.elapsedRealtime())) {
                        Log.i(TAG, "Too many commands over Bluetooth: ignored")
                        return@post
                    }
                    c.commandeValide = true
                    armer(c, Proximite.DUREE_APRES_COMMANDE_MS)
                    surCommande(texte, device.address)
                } finally {
                    if (verrou?.isHeld == true) verrou.release()
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (!dansLeBudget(device)) return
            // Écritures longues (préparées) non prises en charge.
            repondre(device, requestId, Proximite.GATT_REQUEST_NOT_SUPPORTED, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!dansLeBudget(device)) return
            repondre(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (!dansLeBudget(device)) return
            if (descriptor.uuid != Proximite.UUID_CCCD) {
                repondre(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, null)
                return
            }
            val valeur = if (device.address in abonnes) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            repondre(device, requestId, Proximite.GATT_SUCCESS, valeur.copyOfRange(offset.coerceIn(0, 2), 2))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (!dansLeBudget(device)) return
            val ok = descriptor.uuid == Proximite.UUID_CCCD && !preparedWrite && offset == 0 &&
                value != null && value.size == 2
            if (responseNeeded) {
                repondre(
                    device,
                    requestId,
                    if (ok) Proximite.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    null,
                )
            }
            if (!ok) return
            val active = (value!![0].toInt() and 0x01) != 0
            principal.post {
                if (serveur == null) return@post
                // Premier accès au service Sonne : la connexion est comptée à partir d'ici.
                val c = admettre(device) ?: return@post
                c.abonne = active
                if (active) abonnes.add(device.address) else abonnes.remove(device.address)
            }
        }
    }

    /** Verrou partiel de 5 s au plus, pour une écriture reçue pendant que le téléphone dort. */
    private fun verrouCourt(): PowerManager.WakeLock? =
        try {
            contexte.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:gatt")
                .apply {
                    setReferenceCounted(false)
                    acquire(5_000L)
                }
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val TAG = "SonneGatt"
        private const val MTU_DEFAUT = 23 // MTU par défaut d'ATT

        /** Une adresse coupée par Sonne n'est plus admise avant sa déconnexion (ou ce délai). */
        private const val OUBLI_COUPURE_MS = 60_000L
    }
}
