package dev.sidali.sonne

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** État déchiffré reçu en réponse à une commande. */
data class EtatRecu(val sonne: Boolean, val batterie: Int, val enCharge: Boolean, val modele: String, val app: String)

/** Issue de l'envoi d'une commande depuis l'écran Nearby. */
sealed class Resultat {
    /** Accusée par Bluetooth (état frais avec ack = nonce). */
    data class ParBluetooth(val etat: EtatRecu) : Resultat()

    /** Passée par le relais : livrée au téléphone, ou mise en file s'il est hors ligne. */
    data class ParRelais(val livree: Boolean, val enFile: Boolean) : Resultat()

    /**
     * Écriture acceptée par Bluetooth, mais aucun accusé n'est revenu et le relais a échoué :
     * la commande a peut-être été exécutée (téléphone lent), ce n'est pas un échec certain.
     */
    data object EcriteSansAccuse : Resultat()

    /** Ni Bluetooth ni relais. code : statut HTTP, 0 sans réponse. */
    data class Echec(val code: Int) : Resultat()
}

/**
 * Envoi d'une commande à un autre téléphone (section « Faire sonner » du protocole) : par
 * GATT s'il est à portée, en essayant tour à tour les adresses récentes de sa balise (la plus
 * forte d'abord : id_ble est public et peut être recopié), puis, en cas d'échec, le même
 * message, avec le même nonce, par le relais. Le téléphone ne l'exécutera jamais deux fois.
 * Les rappels arrivent sur le fil principal.
 *
 * Format (PROTOCOL.md, « Transition, then cut-off ») : commande chiffrée v3 (172 octets, ni le type ni les
 * paramètres ne sont lisibles en route) si le téléphone a annoncé cmdv ≥ 3 dans un état frais,
 * ou toujours après la coupure ; sinon commande signée v2. Un 400 du relais à une v2 (relais
 * déjà coupé) fait renvoyer la même intention en v3, avec un nouveau nonce ; jamais l'inverse.
 * Tout état GATT frais qui annonce cmdv ≥ 3 est retenu pour cette chaîne (Chaines.marquerCmdV3).
 *
 * @param automatique vérification lancée sans geste de l'utilisateur (réveil du mode recherche) :
 *   pas d'envoi si la présence dit le téléphone hors ligne (le relais la mettrait en file).
 */
@SuppressLint("MissingPermission")
class EnvoiCommande(
    context: Context,
    private val chaine: Chaine,
    private val cmd: String,
    private val fraicheur: FraicheurEtats,
    private val automatique: Boolean = false,
    private val fin: (Resultat) -> Unit,
) {
    private val contexte = context.applicationContext ?: context
    private val principal = Handler(Looper.getMainLooper())

    /** Commande chiffrée (v3) ou signée (v2). Passe à true, jamais l'inverse, sur un 400 du relais. */
    private var v3 = chaine.envoieV3

    /** Nonce de la commande (24 hex en v3, 32 en v2) : c'est l'ack attendu. */
    private var nonce = ""

    /** Message exact envoyé par GATT puis, au besoin, par le relais (172 octets en v3). */
    private var message = ""

    init {
        preparer()
    }

    /** Fabrique le message au format courant, avec un nonce et un ts neufs. */
    private fun preparer() {
        val ts = System.currentTimeMillis()
        if (v3) {
            val n = Crypto.aleatoire(Crypto.TAILLE_NONCE_COMMANDE)
            nonce = Crypto.hex(n)
            // Pas d'interface pour la durée ni la lampe en 1.6 : 0 et 0, le réglage du téléphone.
            message = Crypto.chiffrerCommande(chaine.kCmd, chaine.id, cmd, ts, n)
        } else {
            nonce = Crypto.hex(Crypto.aleatoire(16))
            message = Crypto.messageCommandeV2(chaine.kSig, cmd, ts, nonce)
        }
    }

    /** Capacité déjà retenue pendant cet envoi. */
    private var capaciteVue = false

    /** Le téléphone a annoncé les commandes chiffrées : retenu pour toujours dans la liste chiffrée. */
    private fun apprendreCapacite() {
        if (chaine.cmdv3 || capaciteVue) return
        capaciteVue = true
        try {
            Chaines.marquerCmdV3(contexte, chaine.id)
            Log.i(TAG, "Encrypted commands announced by ${chaine.id.take(6)}")
        } catch (e: Exception) {
            Log.w(TAG, "Capability not saved: ${e.javaClass.simpleName}")
        }
    }

    private var termine = false
    private val candidats = ArrayDeque<BluetoothDevice>()

    /** Au moins une écriture de la commande a été acceptée par Bluetooth. */
    private var ecriteParBluetooth = false

    /**
     * Lance l'envoi : Bluetooth d'abord sur les appareils donnés (ceux du ScanResult, qui
     * portent le bon type d'adresse), dans l'ordre ; liste vide : relais seulement.
     */
    fun lancer(appareils: List<BluetoothDevice>) {
        if (appareils.isNotEmpty() && Permissions.bluetoothChercheur(contexte) && Permissions.bluetoothAllume(contexte)) {
            candidats.addAll(appareils.take(Proximite.ADRESSES_ESSAYEES_MAX))
            Log.i(TAG, "Sending $cmd over Bluetooth (GATT, ${candidats.size} address(es), v${if (v3) 3 else 2})")
        }
        essayerSuivant()
    }

    /** Adresse suivante, ou le relais quand il n'y en a plus. */
    private fun essayerSuivant() {
        if (termine) return
        val appareil = candidats.removeFirstOrNull()
        if (appareil == null) parRelais() else TentativeGatt(appareil).lancer()
    }

    private fun terminer(r: Resultat) {
        if (termine) return
        termine = true
        fin(r)
    }

    /** Échec du relais : pas un échec certain si la commande a été écrite par Bluetooth. */
    private fun echecRelais(code: Int): Resultat =
        if (ecriteParBluetooth && code != 429) Resultat.EcriteSansAccuse else Resultat.Echec(code)

    // -----------------------------------------------------------------
    // Relais
    // -----------------------------------------------------------------

    /** Relais : d'abord la présence pour une vérification automatique, sinon l'envoi direct. */
    private fun parRelais() {
        if (termine) return
        if (automatique) verifierPresence() else envoyerAuRelais()
    }

    /**
     * Une vérification automatique n'est envoyée que si le relais dit le téléphone en ligne :
     * une commande v3 serait sinon mise en file, et livrée plus tard sans raison.
     */
    private fun verifierPresence() {
        val requete = try {
            Request.Builder()
                .url(chaine.relais.trim().trimEnd('/') + "/api/presence")
                // L'id passe dans un en-tête, jamais dans l'URL.
                .header("X-Sonne-Id", chaine.id)
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            principal.post { terminer(echecRelais(0)) }
            return
        }
        client.newCall(requete).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "Relay unreachable: ${e.javaClass.simpleName}")
                principal.post { terminer(echecRelais(0)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val (code, enLigne) = response.use {
                    val json = try {
                        JSONObject(it.body?.string() ?: "{}")
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    it.code to (it.isSuccessful && json.optBoolean("online"))
                }
                principal.post {
                    when {
                        code !in 200..299 -> terminer(echecRelais(code))
                        enLigne -> envoyerAuRelais()
                        else -> {
                            Log.i(TAG, "Phone offline: automatic $cmd not sent")
                            terminer(Resultat.ParRelais(livree = false, enFile = false))
                        }
                    }
                }
            }
        })
    }

    private fun envoyerAuRelais() {
        if (termine) return
        Log.i(TAG, "Sending $cmd through the relay (v${if (v3) 3 else 2})")
        val url = chaine.relais.trim().trimEnd('/') + "/api/cmd"
        val requete = try {
            Request.Builder()
                .url(url)
                // L'id passe dans un en-tête, jamais dans l'URL.
                .header("X-Sonne-Id", chaine.id)
                .post(message.toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            principal.post { terminer(echecRelais(0)) }
            return
        }
        client.newCall(requete).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "Relay unreachable: ${e.javaClass.simpleName}")
                principal.post { terminer(echecRelais(0)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val r = response.use {
                    if (!it.isSuccessful) {
                        echecRelais(it.code)
                    } else {
                        val json = try {
                            JSONObject(it.body?.string() ?: "{}")
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        Resultat.ParRelais(json.optBoolean("delivered"), json.optBoolean("queued"))
                    }
                }
                principal.post {
                    if (code == 400 && !v3 && !termine) {
                        // Relais déjà coupé : il refuse l'ancien format, qui n'a donc pas été livré.
                        // Même intention en v3, avec un nouveau nonce. Jamais de retour à v2 ensuite.
                        Log.i(TAG, "Old format refused by the relay: sending $cmd again, encrypted")
                        v3 = true
                        preparer()
                        envoyerAuRelais()
                    } else {
                        terminer(r)
                    }
                }
            }
        })
    }

    // -----------------------------------------------------------------
    // Bluetooth (GATT)
    // -----------------------------------------------------------------

    /**
     * Lit un état notifié : déchiffré, frais, et accusant notre commande. null sinon. Tout état
     * frais et authentifié qui annonce cmdv ≥ 3 est retenu, même s'il n'accuse pas cette commande.
     */
    private fun lireEtat(valeur: ByteArray): EtatRecu? {
        return try {
            val json = JSONObject(String(valeur, Charsets.UTF_8))
            if (json.optString("type") != "state") return null
            val clair = JSONObject(Crypto.dechiffrerEtat(chaine.kEnc, chaine.id, json.getString("nonce"), json.getString("enc")))
            if (!fraicheur.accepter(chaine.id, clair.getLong("ts"), System.currentTimeMillis())) return null
            if (Crypto.accepteV3(clair.opt(Crypto.CHAMP_CAPACITE))) apprendreCapacite()
            if (clair.optString("ack") != nonce) return null
            EtatRecu(
                sonne = clair.optBoolean("ringing"),
                batterie = clair.optInt("battery", -1),
                enCharge = clair.optBoolean("charging"),
                modele = clair.optString("model"),
                app = clair.optString("app"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Une tentative GATT sur un appareil : connexion directe, MTU, découverte, abonnement,
     * écriture, attente de l'accusé. Un échec rapide de la connexion (133, 62 ou 8 avant 3 s,
     * fréquents à la première connexion) vaut un second essai dans le même budget de 5 s.
     */
    private inner class TentativeGatt(private val appareil: BluetoothDevice) : BluetoothGattCallback() {

        private var gatt: BluetoothGatt? = null
        private var fini = false
        private var commandeEcrite = false
        private var reessaye = false
        private var debut = 0L

        private val delaiDepasse = Runnable { abandon("timed out") }

        fun lancer() {
            debut = SystemClock.elapsedRealtime()
            // Connexion directe (pas d'autoConnect), 5 s au plus, second essai compris.
            principal.postDelayed(delaiDepasse, DELAI_CONNEXION_MS)
            connecter()
        }

        private fun connecter() {
            gatt = try {
                appareil.connectGatt(contexte, false, this, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                null
            }
            if (gatt == null) abandon("cannot connect")
        }

        /** Échec à une étape : on se déconnecte et on passe à l'adresse suivante, puis au relais. */
        private fun abandon(raison: String) {
            if (fini) return
            fini = true
            Log.i(TAG, "Bluetooth given up ($raison)")
            fermer()
            essayerSuivant()
        }

        private fun reussite(etat: EtatRecu) {
            if (fini) return
            fini = true
            Log.i(TAG, "Acknowledged over Bluetooth: $cmd, ringing ${etat.sonne}, battery ${etat.batterie}%")
            fermer()
            terminer(Resultat.ParBluetooth(etat))
        }

        private fun fermerGatt() {
            gatt?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (_: Exception) {
                }
            }
            gatt = null
        }

        private fun fermer() {
            principal.removeCallbacks(delaiDepasse)
            fermerGatt()
        }

        private fun relancerDelai(ms: Long) {
            principal.removeCallbacks(delaiDepasse)
            principal.postDelayed(delaiDepasse, ms)
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            principal.post {
                if (fini || g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    relancerDelai(DELAI_ECHANGE_MS)
                    if (!g.requestMtu(Proximite.MTU_DEMANDE)) abandon("MTU not requested")
                } else if (!commandeEcrite && !reessaye && Proximite.connexionReessayable(status) &&
                    SystemClock.elapsedRealtime() - debut < Proximite.REESSAI_AVANT_MS
                ) {
                    // Échec rapide et classique : on libère la connexion ratée et on réessaie une fois.
                    reessaye = true
                    Log.i(TAG, "GATT connection failed ($status): second try")
                    fermerGatt()
                    principal.postDelayed({ if (!fini) connecter() }, Proximite.PAUSE_REESSAI_MS)
                } else {
                    abandon("disconnected ($status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            principal.post {
                if (fini || g !== gatt) return@post
                // Pas de fragmentation : sans un MTU suffisant, on passe par le relais.
                if (status != BluetoothGatt.GATT_SUCCESS || mtu < Proximite.MTU_MIN) {
                    abandon("MTU too small ($mtu)")
                } else if (!g.discoverServices()) {
                    abandon("service discovery failed")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            principal.post {
                if (fini || g !== gatt) return@post
                val etat = g.getService(Proximite.UUID_SERVICE)?.getCharacteristic(Proximite.UUID_ETAT)
                val cccd = etat?.getDescriptor(Proximite.UUID_CCCD)
                if (status != BluetoothGatt.GATT_SUCCESS || etat == null || cccd == null) {
                    abandon("Sonne service missing")
                    return@post
                }
                g.setCharacteristicNotification(etat, true)
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!ok) abandon("cannot subscribe")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            principal.post {
                if (fini || g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    abandon("subscription refused ($status)")
                    return@post
                }
                val commande = g.getService(Proximite.UUID_SERVICE)?.getCharacteristic(Proximite.UUID_COMMANDE)
                if (commande == null) {
                    abandon("Command characteristic missing")
                    return@post
                }
                val valeur = message.toByteArray(Charsets.UTF_8)
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(commande, valeur, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    commande.value = valeur
                    commande.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(commande)
                }
                if (!ok) {
                    abandon("cannot write")
                    return@post
                }
                commandeEcrite = true
                Log.i(TAG, "Command $cmd written over GATT")
                // 5 s au plus pour l'état qui accuse la commande.
                relancerDelai(Proximite.DELAI_ACCUSE_MS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            principal.post {
                if (fini || g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    ecriteParBluetooth = true
                } else {
                    abandon("write refused ($status)")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            recu(g, characteristic, value)
        }

        @Deprecated("Replaced since Android 13 by the overload that receives the value")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android 13 et plus appellent aussi la nouvelle version : on ne traite qu'une fois.
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            recu(g, characteristic, characteristic.value ?: return)
        }

        private fun recu(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != Proximite.UUID_ETAT) return
            val copie = value.copyOf()
            principal.post {
                if (fini || !commandeEcrite || g !== gatt) return@post
                lireEtat(copie)?.let { reussite(it) }
            }
        }
    }

    companion object {
        private const val TAG = "SonneEnvoi"
        private const val DELAI_CONNEXION_MS = 5_000L
        private const val DELAI_ECHANGE_MS = 5_000L

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
