package dev.sidali.sonne

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Chercheur (section « Chercheur » du protocole) : scan BLE filtré sur les balises Sonne,
 * reconnaissance des chaînes connues par id_ble (fenêtres w - 1, w, w + 1), signal lissé
 * adresse par adresse (id_ble est public et peut être recopié), jauge, bips et retour
 * haptique. Ne tourne que tant que l'écran Nearby est au premier plan.
 * Tout se passe sur le fil principal (les rappels du scanner y arrivent).
 * Les permissions (BLUETOOTH_SCAN) sont vérifiées par l'écran avant demarrer().
 */
@SuppressLint("MissingPermission")
class Chercheur(context: Context) {

    /** Ce que l'écran affiche pour une chaîne. */
    data class Vue(
        val niveau: Int,
        val derniereMesure: Int,
        /** Depuis la dernière mesure, en ms ; -1 si jamais vue depuis l'ouverture de l'écran. */
        val vueIlYaMs: Long,
        /** Plusieurs adresses émettent cet id_ble en même temps : copie probable. */
        val copie: Boolean = false,
    )

    private val contexte = context.applicationContext ?: context
    private val principal = Handler(Looper.getMainLooper())

    private var chaines: Map<String, Chaine> = emptyMap()
    private var reconnaissance = Reconnaissance(emptyMap())
    private val suivis = HashMap<String, SuiviBalises>()

    /** Dernier BluetoothDevice vu par adresse : il porte le type d'adresse (aléatoire) du ScanResult. */
    private val appareils = HashMap<String, BluetoothDevice>()

    private var actif = false
    private var enScan = false
    private var suspendu = false
    private var dernierDemarrageScan = 0L // elapsedRealtime, 0 : jamais
    private var dernierResultat = 0L // elapsedRealtime du dernier résultat Sonne reconnu, 0 : aucun
    private var sonnerie: ToneGenerator? = null

    /** La chaîne suivie par les bips et la vibration (celle affichée à l'écran). */
    var cible: String? = null

    /** Bips activés (le retour haptique reste actif). */
    var bips: Boolean = true

    /** Vues par id de chaîne, publiées 4 fois par seconde. */
    val vues = MutableStateFlow<Map<String, Vue>>(emptyMap())

    /** Code d'échec du scan (ScanCallback.SCAN_FAILED_*), null si tout va bien. */
    val erreurScan = MutableStateFlow<Int?>(null)

    /** Remplace la liste des chaînes recherchées. */
    fun chaines(liste: List<Chaine>) {
        chaines = liste.associateBy { it.id }
        reconnaissance = Reconnaissance(liste.associate { it.id to it.cleBle })
        reconnaissance.maj(Crypto.fenetreBle(System.currentTimeMillis()))
        suivis.keys.retainAll(chaines.keys)
    }

    fun demarrer() {
        if (actif) return
        actif = true
        sonnerie = try {
            // Son généré sur le flux des notifications (suit le volume des notifications).
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_BIP)
        } catch (e: Exception) {
            null
        }
        // L'état du Bluetooth suivi en direct : une pile qui redémarre entre deux lectures de
        // l'écran laisserait sinon un scanner mort jusqu'à 25 min.
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                contexte, suiviBluetooth, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
        demarrerScan()
        principal.post(horloge)
        principal.post(bip)
    }

    fun arreter() {
        if (!actif) return
        actif = false
        principal.removeCallbacks(horloge)
        principal.removeCallbacks(bip)
        principal.removeCallbacks(relanceScan)
        try {
            contexte.unregisterReceiver(suiviBluetooth)
        } catch (_: Exception) {
        }
        arreterScan()
        suspendu = false
        dernierResultat = 0L
        sonnerie?.release()
        sonnerie = null
        // Plus de mesures : rien ne doit rester affiché comme « vu il y a 1 s » ni passer pour à portée.
        suivis.clear()
        appareils.clear()
        idBleJournalises.clear()
        vues.value = emptyMap()
    }

    /**
     * Appareils récents de la balise d'une chaîne, le plus fort d'abord (ils changent à chaque
     * rotation ; plusieurs en même temps si quelqu'un recopie l'id_ble).
     */
    fun appareils(id: String): List<BluetoothDevice> =
        suivis[id]?.adresses(SystemClock.elapsedRealtime())?.mapNotNull { appareils[it] }.orEmpty()

    /** true si la balise de cette chaîne a été vue il y a moins de 10 s. */
    fun aPortee(id: String): Boolean = suivis[id]?.vueRecemment(SystemClock.elapsedRealtime()) == true

    /**
     * Met le scan en pause pendant une connexion GATT : sur beaucoup de contrôleurs, un scan à
     * 100 % et l'ouverture d'une connexion se partagent la radio (connexion lente, statut 133).
     */
    fun suspendre() {
        if (!actif || suspendu) return
        suspendu = true
        principal.removeCallbacks(relanceScan)
        arreterScan()
    }

    /** Reprend le scan après la connexion GATT (en respectant la limite de démarrages). */
    fun reprendre() {
        if (!actif || !suspendu) return
        suspendu = false
        demarrerScan()
    }

    /** Bluetooth rallumé : le scanner d'avant est mort avec la pile, on en relance un. */
    private val suiviBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    enScan = false
                    if (actif && !suspendu) demarrerScan()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> enScan = false
            }
        }
    }

    // -----------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------

    private val rappelScan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = traiter(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { traiter(it) }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan refused (code $errorCode)")
            enScan = false
            erreurScan.value = errorCode
            programmerRelance(RELANCE_ECHEC_MS)
        }
    }

    private val relanceScan = Runnable { if (actif && !suspendu) demarrerScan() }

    private fun programmerRelance(delaiMs: Long) {
        principal.removeCallbacks(relanceScan)
        principal.postDelayed(relanceScan, delaiMs)
    }

    /**
     * Android refuse en silence plus de 5 démarrages en 30 s et bascule en scan opportuniste
     * après 30 min : on redémarre toutes les 25 min, et jamais plus de 4 fois en 30 s
     * (historique commun à tout le processus, sans attente fixe entre deux démarrages).
     */
    private fun demarrerScan() {
        if (suspendu) return
        val maintenant = SystemClock.elapsedRealtime()
        val attente = historique.attente(maintenant)
        if (attente > 0) {
            programmerRelance(attente)
            return
        }
        arreterScan()
        val scanner = try {
            contexte.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        } catch (e: Exception) {
            null
        }
        if (scanner == null) {
            // Bluetooth éteint : relance au retour du Bluetooth (suiviBluetooth), ou plus tard.
            programmerRelance(RELANCE_ECHEC_MS)
            return
        }
        // La balise ne publie pas de liste d'UUID : le filtre porte sur les données de service.
        val filtre = ScanFilter.Builder()
            .setServiceData(ParcelUuid(Proximite.UUID_SERVICE), Proximite.donneesFiltre(), Proximite.masqueFiltre())
            .build()
        val reglages = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            historique.noter(maintenant)
            scanner.startScan(listOf(filtre), reglages, rappelScan)
            enScan = true
            erreurScan.value = null
            dernierDemarrageScan = maintenant
            programmerRelance(DUREE_SCAN_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the scan: ${e.message}")
            programmerRelance(RELANCE_ECHEC_MS)
        }
    }

    private fun arreterScan() {
        if (!enScan) return
        enScan = false
        try {
            contexte.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(rappelScan)
        } catch (_: Exception) {
        }
    }

    private fun traiter(resultat: ScanResult) {
        if (!actif) return
        val donnees = resultat.scanRecord?.getServiceData(ParcelUuid(Proximite.UUID_SERVICE)) ?: return
        reconnaissance.maj(Crypto.fenetreBle(System.currentTimeMillis()))
        // Une balise inconnue est ignorée sans trace.
        val id = reconnaissance.reconnaitre(donnees) ?: return
        val instant = SystemClock.elapsedRealtime()
        val adresse = resultat.device.address
        // Par adresse : un clone qui réémet l'id_ble ne détourne ni la jauge d'une autre adresse ni l'envoi.
        suivis.getOrPut(id) { SuiviBalises() }.mesure(adresse, resultat.rssi, instant)
        appareils[adresse] = resultat.device
        dernierResultat = instant
        if (BuildConfig.DEBUG) journalReconnaissance(id, donnees, resultat)
    }

    // -----------------------------------------------------------------
    // Journal de test (debug seulement, supprimé de la release par R8)
    // -----------------------------------------------------------------

    private val idBleJournalises = HashSet<String>()
    private var dernierJournalSignal = 0L

    /** Première fois qu'un id_ble est reconnu : chaîne, fenêtre, adresse et mesure. */
    private fun journalReconnaissance(id: String, donnees: ByteArray, resultat: ScanResult) {
        val idBle = Proximite.lireIdBle(donnees) ?: return
        val hex = Crypto.hex(idBle)
        if (!idBleJournalises.add(hex)) return
        val c = chaines[id] ?: return
        val w = Crypto.fenetreBle(System.currentTimeMillis())
        val ecart = (-1L..1L).firstOrNull { Crypto.idBle(c.cleBle, w + it).contentEquals(idBle) }
        Log.i(TAG, "Beacon recognized: chain ${id.take(6)}, id_ble $hex, window w${if (ecart == null) " ?" else if (ecart >= 0) "+$ecart" else "$ecart"} ($w), address ${resultat.device.address}, ${resultat.rssi} dBm")
    }

    /** Toutes les 2 s : signal brut, lissé et niveau de chaque chaîne à portée. */
    private fun journalSignal(instant: Long) {
        if (instant - dernierJournalSignal < 2_000L) return
        dernierJournalSignal = instant
        for ((id, balises) in suivis) {
            val s = balises.meilleur(instant) ?: continue
            Log.i(TAG, "Signal ${id.take(6)}: raw ${s.derniereMesure} dBm, smoothed ${"%.1f".format(s.lisse ?: 0.0)} dBm, level ${s.niveau(instant)}, seen ${instant - s.dernierVu} ms ago, ${balises.adresses(instant).size} address(es)")
        }
    }

    // -----------------------------------------------------------------
    // Affichage, bips et vibration
    // -----------------------------------------------------------------

    private val horloge = object : Runnable {
        override fun run() {
            if (!actif) return
            reconnaissance.maj(Crypto.fenetreBle(System.currentTimeMillis()))
            val instant = SystemClock.elapsedRealtime()
            vues.value = chaines.keys.associateWith { id ->
                val balises = suivis[id]
                val s = balises?.meilleur(instant)
                if (balises == null || s == null) {
                    Vue(0, 0, -1L)
                } else {
                    Vue(s.niveau(instant), s.derniereMesure, instant - s.dernierVu, balises.copieProbable(instant))
                }
            }
            // Les adresses oubliées par tous les suivis ne servent plus.
            if (appareils.size > 32) {
                val utiles = suivis.values.flatMap { it.adresses(instant) }.toSet()
                appareils.keys.retainAll(utiles)
            }
            surveillerScan(instant)
            if (BuildConfig.DEBUG) journalSignal(instant)
            principal.postDelayed(this, 250L)
        }
    }

    /**
     * Garde-fou : un scan qui tourne sans plus rien remonter depuis 60 s alors qu'une balise
     * était vue est relancé (scanner bloqué par la pile). Sans effet si la balise s'est
     * simplement éteinte : la relance ne coûte qu'un démarrage, dans la limite de 4 en 30 s.
     */
    private fun surveillerScan(instant: Long) {
        if (!enScan || suspendu || dernierResultat <= 0) return
        if (instant - dernierResultat < SILENCE_MAX_MS || instant - dernierDemarrageScan < SILENCE_MAX_MS) return
        Log.i(TAG, "Scan silent for 60 s: restarting")
        arreterScan()
        demarrerScan()
    }

    private val bip = object : Runnable {
        override fun run() {
            if (!actif) return
            val id = cible
            val instant = SystemClock.elapsedRealtime()
            val niveau = if (id == null) 0 else suivis[id]?.meilleur(instant)?.niveau(instant) ?: 0
            if (niveau <= 0) {
                principal.postDelayed(this, 300L)
                return
            }
            if (bips) {
                try {
                    sonnerie?.startTone(ToneGenerator.TONE_PROP_BEEP, DUREE_BIP_MS)
                } catch (_: Exception) {
                }
            }
            vibrer(niveau)
            principal.postDelayed(this, Proximite.RYTHME_BIP_MS[niveau.coerceIn(1, Proximite.NIVEAU_MAX)])
        }
    }

    /** Petite impulsion, plus longue et plus forte quand on se rapproche. */
    private fun vibrer(niveau: Int) {
        try {
            val vibreur = contexte.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!vibreur.hasVibrator()) return
            val amplitude = if (vibreur.hasAmplitudeControl()) (40 + 43 * niveau).coerceAtMost(255) else VibrationEffect.DEFAULT_AMPLITUDE
            vibreur.vibrate(VibrationEffect.createOneShot(15L + 7L * niveau, amplitude))
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "SonneChercheur"
        const val DUREE_SCAN_MS = 25L * 60 * 1000

        /** Nouvel essai après un échec de démarrage (Bluetooth éteint, scan refusé). */
        const val RELANCE_ECHEC_MS = 6_000L

        /** Scan sans aucun résultat Sonne depuis ce délai, alors qu'une balise était vue : relance. */
        const val SILENCE_MAX_MS = 60_000L

        /** Démarrages de scan : commun à tout le processus (Android compte par app). */
        val historique = HistoriqueDemarrages()
        const val VOLUME_BIP = 80
        const val DUREE_BIP_MS = 70
    }
}
