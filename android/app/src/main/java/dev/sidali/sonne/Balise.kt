package dev.sidali.sonne

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Balise Bluetooth du téléphone protégé (section « Balise » du protocole) : publicité legacy
 * connectable, une seule structure « Service Data, UUID 128 bits » avec id_ble puis la version,
 * intervalle de 250 ms, faible puissance. Ni nom d'appareil, ni puissance d'émission.
 * Elle n'émet qu'en mode recherche (VeilleService décide quand).
 *
 * À chaque nouvelle fenêtre de 15 min, l'ensemble est arrêté puis relancé : la pile tire une
 * nouvelle adresse aléatoire. L'adresse peut aussi changer seule en cours de fenêtre (minuterie
 * de l'adresse privée, propre à la pile), ce qui ne relie rien.
 * Toutes les méthodes s'exécutent sur le fil principal (les rappels y arrivent aussi).
 * Les permissions (BLUETOOTH_ADVERTISE) sont vérifiées par l'appelant, VeilleService.
 *
 * @param surChangement appelé sur le fil principal quand la pile confirme l'émission (true),
 *   la refuse ou l'arrête (false). C'est la seule source de vérité de « la balise émet ».
 */
@SuppressLint("MissingPermission")
class Balise(context: Context, private val surChangement: (emet: Boolean) -> Unit) {

    private val contexte = context.applicationContext ?: context

    private var rappel: AdvertisingSetCallback? = null
    private var ensemble: AdvertisingSet? = null
    private var fenetre = -1L
    private var cleCourante = ByteArray(0)

    /** true entre une demande de démarrage et l'arrêt (ou l'échec signalé par la pile). */
    var active = false
        private set

    /**
     * S'assure que la balise émet l'identifiant de la fenêtre courante pour la clé B.
     * Ne fait rien si c'est déjà le cas ; sinon (nouvelle fenêtre, nouvelle clé après « Changer
     * la clé », échec précédent) arrête l'ancien ensemble et en démarre un neuf.
     */
    fun assurer(cleBle: ByteArray, maintenantMs: Long) {
        val w = Crypto.fenetreBle(maintenantMs)
        if (active && w == fenetre && cleBle.contentEquals(cleCourante)) return
        demarrer(cleBle, w)
    }

    /**
     * Après la déconnexion d'un client GATT : une publicité connectable est arrêtée par le
     * contrôleur à la première connexion, et toutes les piles ne la relancent pas. On réactive
     * l'ensemble existant (même adresse, sans effet s'il émet déjà) ; à défaut, on en démarre un neuf.
     */
    fun relancer(cleBle: ByteArray, maintenantMs: Long) {
        if (!active) return
        val set = ensemble
        if (set != null) {
            try {
                set.enableAdvertising(true, 0, 0)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot re-enable the beacon: ${e.message}")
            }
        }
        demarrer(cleBle, Crypto.fenetreBle(maintenantMs))
    }

    private fun demarrer(cleBle: ByteArray, w: Long) {
        arreter()
        val adaptateur = contexte.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val emetteur = try {
            adaptateur.bluetoothLeAdvertiser
        } catch (e: Exception) {
            null
        } ?: run {
            Log.w(TAG, "BLE advertising unavailable (Bluetooth off or not supported)")
            return
        }
        val idBle = Crypto.idBle(cleBle, w)
        // Type d'adresse : celui de la pile (adresse privée résolvable). Une adresse non
        // résolvable demanderait setOwnAddressType, réservé aux apps système
        // (BLUETOOTH_PRIVILEGED) : voir « Limites assumées » du protocole.
        val parametres = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true) // obligatoire pour une publicité legacy connectable
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM) // 250 ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_LOW) // -15 dBm environ
            .build()
        val donnees = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(Proximite.UUID_SERVICE), Proximite.donneesService(idBle))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (rappel !== this) return
                if (status == ADVERTISE_SUCCESS) {
                    ensemble = set
                    // En debug seulement : la table fenêtre / id_ble relierait les fenêtres entre
                    // elles si elle sortait dans un rapport de bug.
                    if (BuildConfig.DEBUG) Log.i(TAG, "Beacon on air (window $w, id_ble ${Crypto.hex(idBle)}, $txPower dBm)")
                    surChangement(true)
                } else {
                    // Trop d'émetteurs, erreur interne… : VeilleService refait un essai court.
                    Log.w(TAG, "Beacon refused by the Bluetooth stack (code $status)")
                    active = false
                    fenetre = -1L
                    rappel = null
                    ensemble = null
                    surChangement(false)
                }
            }

            override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
                // Certaines piles désactivent l'ensemble à la première connexion (publicité legacy
                // connectable) sans l'arrêter : on le note pour qu'assurer() le relance.
                if (rappel !== this) return
                if ((!enable && status == ADVERTISE_SUCCESS) || (enable && status != ADVERTISE_SUCCESS)) {
                    // Désactivé par la pile, ou réactivation refusée : assurer() repartira d'un ensemble neuf.
                    if (enable) Log.w(TAG, "Beacon re-enable refused (code $status)")
                    active = false
                    fenetre = -1L
                    surChangement(false)
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                if (rappel === this) {
                    active = false
                    fenetre = -1L
                    rappel = null
                    ensemble = null
                    surChangement(false)
                }
            }
        }
        try {
            // Réponse au scan vide : rien de plus que les données de service.
            emetteur.startAdvertisingSet(parametres, donnees, AdvertiseData.Builder().build(), null, null, cb)
            rappel = cb
            active = true
            fenetre = w
            cleCourante = cleBle.copyOf()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the beacon: ${e.message}")
            surChangement(false)
        }
    }

    /** Arrête l'émission (sans effet si rien n'émet). */
    fun arreter() {
        val cb = rappel
        rappel = null
        ensemble = null
        active = false
        fenetre = -1L
        if (cb == null) return
        try {
            contexte.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            // Bluetooth déjà éteint : l'émission s'est arrêtée avec lui.
        }
    }

    /** Oublie l'état sans appeler la pile (Bluetooth éteint : l'ensemble a disparu avec elle). */
    fun oublier() {
        rappel = null
        ensemble = null
        active = false
        fenetre = -1L
    }

    private companion object {
        const val TAG = "SonneBalise"
    }
}
