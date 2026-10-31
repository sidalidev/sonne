package dev.sidali.sonne

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Veille (section Veille du protocole) : service de premier plan qui garde un WebSocket
 * vers le relais, traite les commandes (chiffrées en v3, signées en v2 pendant la transition)
 * et renvoie des états chiffrés.
 * Toute la logique tourne sur le fil principal ; OkHttp ne fait que poster des messages.
 *
 * Minuteurs : un Handler compte en temps d'éveil et s'arrête quand le processeur dort, ce
 * qu'un service de premier plan n'empêche pas. Tout ce qui doit arriver même téléphone
 * endormi passe donc par le chien de garde (AlarmManager, ELAPSED_REALTIME_WAKEUP, sans
 * permission) : battement applicatif toutes les 5 min, état périodique, reconnexions longues,
 * fin du mode recherche.
 *
 * Proximité Bluetooth : balise et serveur GATT ne tournent qu'en mode recherche
 * (Proximite.modeRecherche) : 10 min après chaque commande acceptée, pendant la sonnerie, ou
 * quand le relais est injoignable depuis une heure. Hors de ce mode, rien n'est émis et
 * personne ne peut se connecter au téléphone pour lire son nom Bluetooth.
 */
class VeilleService : Service() {

    companion object {
        private const val TAG = "SonneVeille"
        private const val NOTIF_VEILLE = 1
        private const val ETAT_PERIODIQUE_MS = 15L * 60 * 1000
        private const val DELAI_MIN_MS = 1_000L
        private const val DELAI_MAX_MS = 60_000L

        /** Au-delà, une reconnexion passe aussi par une alarme (le Handler dormirait avec le processeur). */
        private const val DELAI_COURT_MS = 5_000L

        /** Période du chien de garde et du battement applicatif. */
        const val INTERVALLE_GARDE_MS = 5L * 60 * 1000

        /** Sans réponse au battement après ce délai, la connexion est tenue pour morte. */
        private const val ATTENTE_HB_MS = 20_000L

        /** Une ouverture de connexion qui traîne au-delà est relancée. */
        private const val OUVERTURE_MAX_MS = 60_000L

        /** Recharge le secret et l'adresse du relais, puis se reconnecte tout de suite. */
        const val ACTION_RECONNECTER = "dev.sidali.sonne.action.RECONNECTER"

        /** Réévalue la balise Bluetooth et le serveur GATT (réglage changé, permission accordée). */
        const val ACTION_PROXIMITE = "dev.sidali.sonne.action.PROXIMITE"

        /** Alarme posée à la frontière de fenêtre : nouvel identifiant de balise. */
        const val ACTION_ROTATION = "dev.sidali.sonne.action.ROTATION"

        /** Délais des nouveaux essais de serveur GATT ou de balise après un échec. */
        private val DELAIS_ESSAI_PROXIMITE_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** Le service en cours dans ce processus, pour le chien de garde et le débogage. */
        @Volatile
        var instance: VeilleService? = null
            private set

        /** Démarre (ou réveille) le service, sauf si l'interrupteur principal est sur Off. */
        fun demarrer(context: Context, action: String? = null, source: Marche.Source = Marche.Source.APP) {
            if (!Marche.veilleAutorisee(Marche.actif(context), source)) return
            val intent = Intent(context, VeilleService::class.java).setAction(action)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Service start refused: ${e.message}")
            }
        }

        /** Interrupteur principal sur Off : arrête le service (onDestroy coupe relais, alarmes et Bluetooth). */
        fun arreter(context: Context) {
            context.stopService(Intent(context, VeilleService::class.java))
        }
    }

    private val principal = Handler(Looper.getMainLooper())
    private var demarre = false
    private lateinit var reglages: Reglages
    private lateinit var client: OkHttpClient
    private lateinit var filtre: FiltreCommandes
    private lateinit var verrouReconnexion: PowerManager.WakeLock

    private var secretPret = false
    private var id = ""
    private var preuve = ByteArray(0)
    private var kSig = ByteArray(0)
    private var kCmd = ByteArray(0)
    private var kEnc = ByteArray(0)
    private var cleBle = ByteArray(0)

    // Proximité Bluetooth (v1.1) : balise et serveur GATT, en mode recherche seulement.
    private lateinit var balise: Balise
    private var gatt: ServeurGatt? = null
    private var essaisGatt = 0
    private var rechercheJusqua = 0L // elapsedRealtime : fin du mode recherche ouvert par une commande
    private var horsLigneDepuis = 0L // elapsedRealtime : début de la coupure avec le relais, 0 si joint
    private var recherche = false // dernier mode recherche calculé (pour la notification)
    private lateinit var verrouProximite: PowerManager.WakeLock

    private var socket: WebSocket? = null
    private var generation = 0 // ignore les rappels d'un ancien WebSocket
    private var connecte = false
    private var tentatives = 0
    private var reseauActuel: Network? = null
    private var reseauConnu = false

    // Horodatages en elapsedRealtime : ils avancent aussi quand le téléphone dort.
    private var debutConnexion = 0L
    private var hbEnvoyeA = 0L // 0 : aucun battement sans réponse
    private var dernierEtatEnvoye = 0L

    /** Verrou pris par l'alarme du chien de garde, relâché dès qu'il n'y a plus rien à attendre. */
    private var verrouGarde: PowerManager.WakeLock? = null

    private val reconnexion = Runnable { connecter() }

    /** Fin du mode recherche, ou nouvel essai de serveur GATT ou de balise. */
    private val reevaluerProximite = Runnable {
        majProximite()
        if (verrouProximite.isHeld) verrouProximite.release()
    }

    /** Relance de la balise après la déconnexion d'un client GATT (regroupe les déconnexions proches). */
    private val relanceBalise = Runnable {
        if (secretPret && gatt?.pret == true) balise.relancer(cleBle, System.currentTimeMillis())
    }

    /** Après l'envoi d'un battement : sans aucune réponse, la connexion est à moitié morte. */
    private val verifierBattement = Runnable {
        if (connecte && hbEnvoyeA > 0 && SystemClock.elapsedRealtime() - hbEnvoyeA >= ATTENTE_HB_MS) {
            Log.i(TAG, "No answer to the heartbeat: reconnecting")
            tentatives = 0
            connecter()
        }
    }

    private val surveillanceReseau = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            principal.post {
                // Le premier appel, juste après l'enregistrement, décrit le réseau déjà là.
                val premier = !reseauConnu
                reseauConnu = true
                // Un retour après une perte totale compte comme un changement.
                val change = reseauActuel != network
                reseauActuel = network
                if ((change && !premier) || socket == null) {
                    Log.i(TAG, "Network available, reconnecting now")
                    tentatives = 0
                    connecter()
                }
            }
        }

        override fun onLost(network: Network) {
            principal.post {
                if (network != reseauActuel) return@post
                reseauActuel = null
                // Plus de réseau : l'ancienne connexion est morte, on attend le prochain onAvailable.
                principal.removeCallbacks(reconnexion)
                principal.removeCallbacks(verifierBattement)
                generation++
                socket?.cancel()
                socket = null
                majConnexion(false)
            }
        }
    }

    /** Bluetooth allumé : on relance balise et serveur. Éteint : ils ont disparu avec la pile. */
    private val suiviBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on: proximity re-evaluated")
                    essaisGatt = 0
                    majProximite()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // L'ensemble d'émission a disparu avec la pile : on l'oublie sans l'arrêter.
                    balise.oublier()
                    arreterProximite()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // D'abord remettre le volume et Ne pas déranger si le processus est mort pendant une
        // sonnerie : cela ne doit pas dépendre du succès de la mise au premier plan.
        Ringer.restaurerSiBesoin(this)
        Canaux.creer(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_VEILLE, notificationVeille(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_VEILLE, notificationVeille()) // type lu dans le manifeste
            }
        } catch (e: Exception) {
            // Relance depuis l'arrière-plan refusée (Android 12 et plus, optimisation de batterie
            // active) : on s'arrête proprement au lieu de planter, et de replanter à chaque relance.
            val refus = e is ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Cannot go to the foreground${if (refus) " (refused by the system)" else ""}: ${e.message}")
            stopSelf()
            return
        }
        // Éteinte entre la demande de démarrage et ici : on s'arrête sans rien ouvrir.
        if (!Marche.actif(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        demarre = true
        instance = this

        reglages = Reglages(this)
        filtre = FiltreCommandes(reglages.nonces, reglages.dernierTsOrdre)
        Etat.derniereConnexion.value = reglages.derniereConnexion
        Etat.secretPerdu.value = Coffre.estPerdu(this)
        verrouReconnexion = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:reconnexion")
            .apply { setReferenceCounted(false) }

        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket : pas de délai de lecture
            // Pas de pingInterval : chaque ping réveillerait la radio. Le relais pingue toutes les
            // 4 min et le chien de garde envoie un battement applicatif toutes les 5 min.
            .build()

        Ringer.surChangement = { _, accuse ->
            principal.post {
                // Un seul état par commande : quand le changement vient d'une commande, son
                // accusé est déjà parti avec la bonne valeur de « ringing ». Les autres (arrêt
                // automatique, bouton Stop du téléphone, test local) envoient leur état.
                if (!accuse) {
                    envoyerEtat(null)
                    notifierGatt(null, null)
                }
                // Fin de sonnerie : le mode recherche peut se refermer.
                majProximite()
            }
        }

        verrouProximite = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:proximite")
            .apply { setReferenceCounted(false) }
        // Pas encore joint le relais : la coupure compte depuis le démarrage du service.
        horsLigneDepuis = SystemClock.elapsedRealtime()
        balise = Balise(this) { emet -> surBalise(emet) }
        androidx.core.content.ContextCompat.registerReceiver(
            this, suiviBluetooth, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(surveillanceReseau)
        connecter()
        majProximite()
        programmerGarde(INTERVALLE_GARDE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!demarre) return START_NOT_STICKY
        if (!Marche.actif(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when {
            intent?.action == ACTION_RECONNECTER -> reconnecter()
            intent?.action == ACTION_PROXIMITE -> {
                if (!secretPret) chargerSecret()
                majProximite()
            }
            // Relance (premier déverrouillage, retour dans l'app) alors que le secret manquait.
            !secretPret || socket == null -> {
                tentatives = 0
                connecter()
            }
        }
        return START_STICKY
    }

    /** Recharge le secret et l'adresse du relais, puis se reconnecte tout de suite. */
    fun reconnecter() {
        if (!demarre) return
        secretPret = false
        tentatives = 0
        connecter()
    }

    override fun onDestroy() {
        if (demarre) {
            instance = null
            principal.removeCallbacksAndMessages(null)
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(surveillanceReseau)
            } catch (_: Exception) {
            }
            annulerGarde()
            arreterProximite()
            Etat.modeRecherche.value = false
            try {
                unregisterReceiver(suiviBluetooth)
            } catch (_: Exception) {
            }
            generation++
            socket?.close(1000, null)
            socket = null
            majConnexion(false)
            Ringer.surChangement = null
            if (verrouReconnexion.isHeld) verrouReconnexion.release()
            if (verrouProximite.isHeld) verrouProximite.release()
            relacherGarde()
            client.dispatcher.executorService.shutdown()
        }
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Secret
    // -----------------------------------------------------------------

    /** Charge S et en dérive id, P, K_sig, K_cmd et K_enc. false si S est indisponible ou perdu. */
    private fun chargerSecret(): Boolean {
        val s = try {
            Coffre.secret(this)
        } catch (e: Coffre.Perdu) {
            Log.e(TAG, "Key lost: waiting for the user (home screen)")
            Etat.secretPerdu.value = true
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Key unavailable for now: ${e.message}")
            return false
        }
        id = Crypto.deriveId(s)
        preuve = Crypto.preuve(s)
        kSig = Crypto.cleSignature(s)
        kCmd = Crypto.cleCommande(kSig)
        kEnc = Crypto.cleChiffrement(s)
        cleBle = Crypto.cleBle(s)
        // Version debug seulement : le lien d'appairage dans le journal, pour les tests par adb.
        if (BuildConfig.DEBUG) Log.i("SONNE_PAIR", Crypto.versLien(s, reglages.relais))
        s.fill(0)
        Etat.secretPerdu.value = false
        secretPret = true
        // Nouvelle clé (« Changer la clé ») : la balise repart avec le nouvel identifiant.
        majProximite()
        return true
    }

    // -----------------------------------------------------------------
    // Connexion au relais
    // -----------------------------------------------------------------

    private fun connecter() {
        if (!demarre) return
        principal.removeCallbacks(reconnexion)
        principal.removeCallbacks(verifierBattement)
        val gen = ++generation
        socket?.cancel()
        socket = null
        majConnexion(false)
        hbEnvoyeA = 0L

        if (!secretPret && !chargerSecret()) {
            // Clé perdue : rien à faire avant l'accord de l'utilisateur. Sinon on réessaie.
            if (!Etat.secretPerdu.value) programmerReconnexion()
            return
        }

        val requete = try {
            Request.Builder()
                .url(Reglages.urlWebSocket(reglages.relais))
                // La preuve P (et pas l'id) passe dans un en-tête : rien d'identifiant dans les journaux d'accès.
                .header("Sec-WebSocket-Protocol", Reglages.sousProtocoles(preuve))
                .build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid relay address")
            programmerReconnexion()
            return
        }
        Log.i(TAG, "Connecting to the relay (id ${id.take(6)})")
        debutConnexion = SystemClock.elapsedRealtime()
        socket = client.newWebSocket(requete, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                principal.post {
                    if (gen != generation) return@post
                    majConnexion(true)
                    envoyerEtat(null)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                principal.post { if (gen == generation) traiterMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                principal.post { if (gen == generation) perdu("closed ($code)") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                principal.post { if (gen == generation) perdu(t.javaClass.simpleName) }
            }
        })
    }

    private fun perdu(raison: String) {
        Log.i(TAG, "Connection lost: $raison")
        principal.removeCallbacks(verifierBattement)
        socket = null
        majConnexion(false)
        programmerReconnexion()
        relacherGarde()
    }

    /**
     * Délai croissant : 1 s, 2 s, 4 s… jusqu'à 60 s, plus un peu d'aléa.
     * Délai court : Handler sous un WakeLock. Délai long : Handler et alarme, le premier qui
     * se déclenche reconnecte (l'alarme réveille le téléphone s'il dort).
     */
    private fun programmerReconnexion() {
        principal.removeCallbacks(reconnexion)
        val base = minOf(DELAI_MAX_MS, DELAI_MIN_MS shl minOf(tentatives, 6))
        val delai = base + Random.nextLong(0, base / 4 + 1)
        tentatives++
        principal.postDelayed(reconnexion, delai)
        if (delai <= DELAI_COURT_MS) {
            verrouReconnexion.acquire(delai + 10_000L)
        } else {
            programmerGarde(delai)
        }
    }

    private fun majConnexion(ok: Boolean) {
        if (ok) {
            val maintenant = System.currentTimeMillis()
            reglages.derniereConnexion = maintenant
            Etat.derniereConnexion.value = maintenant
        }
        if (connecte == ok) return
        connecte = ok
        Etat.connecte.value = ok
        // Coupure avec le relais : au bout d'une heure, le mode recherche s'ouvre seul.
        horsLigneDepuis = if (ok) 0L else SystemClock.elapsedRealtime()
        majNotification()
        if (ok) majProximite()
    }

    private fun majNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_VEILLE, notificationVeille())
        } catch (_: SecurityException) {
        }
    }

    // -----------------------------------------------------------------
    // Chien de garde
    // -----------------------------------------------------------------

    private fun intentionGarde(): PendingIntent =
        PendingIntent.getBroadcast(
            this, 0, Intent(this, ChienDeGarde::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private var echeanceGarde = 0L // elapsedRealtime de l'alarme programmée, 0 : aucune

    /**
     * Programme la prochaine alarme dans `delaiMs`, sauf si une alarme plus proche est déjà
     * programmée. Une seule à la fois : la nouvelle remplace l'ancienne.
     */
    private fun programmerGarde(delaiMs: Long) {
        val maintenant = SystemClock.elapsedRealtime()
        val cible = maintenant + delaiMs
        if (echeanceGarde > maintenant && echeanceGarde <= cible) return
        try {
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                cible,
                intentionGarde(),
            )
            echeanceGarde = cible
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog alarm refused: ${e.message}")
        }
    }

    private fun annulerGarde() {
        try {
            getSystemService(AlarmManager::class.java).cancel(intentionGarde())
        } catch (_: Exception) {
        }
    }

    /**
     * Appelé par l'alarme, sous le WakeLock pris par ChienDeGarde (30 s au plus) : le service le
     * garde le temps d'une réponse (battement, accueil du relais, échec de connexion) et le
     * relâche aussitôt, au lieu de tenir le processeur éveillé 30 s toutes les 5 min.
     * Reconnecte si besoin, sinon envoie un battement ; envoie l'état périodique.
     */
    fun chienDeGarde(verrou: PowerManager.WakeLock) {
        relacherGarde()
        verrouGarde = verrou
        if (!demarre) {
            relacherGarde()
            return
        }
        echeanceGarde = 0L
        programmerGarde(INTERVALLE_GARDE_MS)
        // Mise à jour intégrée : une vérification par jour au plus (réglage actif par défaut).
        MiseAJour.verifierSiDu(this)
        // Balise : fin du mode recherche, nouvel essai après un échec, rotation manquée (alarme retardée en Doze).
        majProximite()
        if (Etat.secretPerdu.value) {
            relacherGarde()
            return
        }
        val maintenant = SystemClock.elapsedRealtime()
        when {
            socket == null -> {
                Log.i(TAG, "Watchdog: reconnecting")
                connecter()
            }
            !connecte && maintenant - debutConnexion > OUVERTURE_MAX_MS -> {
                Log.i(TAG, "Watchdog: connection stuck, trying again")
                connecter()
            }
            connecte && hbEnvoyeA > 0 && maintenant - hbEnvoyeA > ATTENTE_HB_MS -> {
                // Le battement précédent est resté sans réponse : connexion à moitié morte.
                Log.i(TAG, "Watchdog: heartbeat unanswered, reconnecting")
                tentatives = 0
                connecter()
            }
            connecte -> envoyerBattement()
        }
        if (connecte && maintenant - dernierEtatEnvoye >= ETAT_PERIODIQUE_MS) envoyerEtat(null)
        // Rien n'attend de réponse (secret indisponible, reconnexion différée) : on relâche tout de suite.
        if (socket == null) relacherGarde()
    }

    /** Relâche le verrou du chien de garde (réponse reçue, connexion perdue, rien à attendre). */
    private fun relacherGarde() {
        verrouGarde?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {
            }
        }
        verrouGarde = null
    }

    /** Appelé par l'alarme de rotation, à la frontière de fenêtre (l'appelant relâche son verrou au retour). */
    fun rotation() {
        if (!demarre) return
        majProximite()
    }

    private fun envoyerBattement() {
        val ws = socket ?: return
        if (hbEnvoyeA == 0L) hbEnvoyeA = SystemClock.elapsedRealtime()
        ws.send("""{"type":"hb"}""")
        principal.removeCallbacks(verifierBattement)
        principal.postDelayed(verifierBattement, ATTENTE_HB_MS)
    }

    // -----------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------

    private fun traiterMessage(texte: String) {
        // Tout message reçu prouve que la connexion vit : le chien de garde n'a plus rien à attendre.
        hbEnvoyeA = 0L
        principal.removeCallbacks(verifierBattement)
        relacherGarde()
        val json = try {
            JSONObject(texte)
        } catch (e: Exception) {
            return
        }
        when (json.optString("type")) {
            "welcome" -> {
                tentatives = 0
                val enAttente = json.optJSONArray("queued") ?: return
                for (i in 0 until enAttente.length()) {
                    enAttente.optJSONObject(i)?.let { traiterCommande(it, null) }
                }
            }
            "cmd" -> traiterCommande(json, null)
            // "hb" : réponse au battement, rien d'autre à faire.
        }
    }

    /** Clés de la chaîne de ce téléphone pour le filtre des commandes. */
    private fun cles() = FiltreCommandes.Cles(id, kSig, kCmd)

    /**
     * Vérifie puis exécute une commande, reçue du relais (adresseGatt null) ou par Bluetooth,
     * chiffrée (v3) ou signée (v2, pendant la transition). Même chemin et même mémoire de
     * nonces pour tous : une commande reçue par l'un puis par l'autre n'est exécutée qu'une
     * fois. Une commande refusée est ignorée en silence.
     *
     * Ordre voulu : mémoire des nonces écrite sur disque (synchrone) AVANT d'exécuter, puis
     * accusé (relais et Bluetooth) AVANT le travail lourd de la sonnerie (préparation du son,
     * volume, Ne pas déranger, écran), qui peut dépasser l'attente du chercheur sur un
     * téléphone lent. L'accusé est le seul état produit par la commande : la sonnerie qu'elle
     * démarre ou arrête n'en envoie pas de second.
     */
    private fun traiterCommande(json: JSONObject, adresseGatt: String?) {
        if (!secretPret) return
        val verrou = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:commande")
        verrou.acquire(10_000L)
        try {
            val avant = filtre.revision
            val issue = filtre.accepter(cles(), json, System.currentTimeMillis())
            // Seulement si la mémoire a changé : une commande refusée avant le rejeu ne coûte
            // pas d'écriture. Elle change aussi pour une ring trop ancienne (gardée mais ignorée).
            if (filtre.revision != avant) reglages.enregistrerFiltre(filtre.nonces(), filtre.dernierTsOrdre)
            val c = when (issue) {
                is FiltreCommandes.Issue.Refusee -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Command refused: ${issue.raison}")
                    return
                }
                is FiltreCommandes.Issue.Acceptee -> issue.commande
            }
            Log.i(TAG, "Command accepted: ${c.cmd}, v${c.format} (${if (adresseGatt != null) "Bluetooth" else "relay"})")
            val sonneApres = when (c.cmd) {
                "ring" -> true
                "stop" -> false
                else -> Ringer.sonne // "ping" : rien à faire, l'état part tout de suite.
            }
            // ack = nonce de la commande (32 hex en v2, 24 en v3).
            envoyerEtat(c.nonce, sonneApres)
            if (adresseGatt != null) notifierGatt(adresseGatt, c.nonce, sonneApres)
            when (c.cmd) {
                "ring" -> {
                    val parametres = Crypto.parametresSonnerie(c.duree, c.lampe)
                    principal.post { Ringer.demarrer(this, parametres, accuse = true) }
                }
                "stop" -> principal.post { Ringer.arreter(this, accuse = true) }
            }
            // Toute commande acceptée ouvre (ou prolonge) le mode recherche : le propriétaire cherche.
            ouvrirRecherche()
        } finally {
            if (verrou.isHeld) verrou.release()
        }
    }

    /**
     * Message d'état chiffré (voir « État » dans le protocole). Le clair, écrit à la main avec
     * "cmdv":3, est bourré à une taille fixe : la longueur du chiffré ne dit rien du modèle ni
     * de la version de l'app.
     */
    private fun messageEtat(ack: String?, modele: String, sonne: Boolean = Ringer.sonne): String {
        val (batterie, enCharge) = batterie()
        val clair = Proximite.clairEtat(
            ts = System.currentTimeMillis(),
            sonne = sonne,
            batterie = batterie,
            enCharge = enCharge,
            app = BuildConfig.VERSION_NAME,
            ack = ack,
            modele = modele,
        )
        val chiffre = Crypto.chiffrerEtat(kEnc, id, Proximite.bourrerEtat(clair))
        return Proximite.messageEtat(chiffre.nonce, chiffre.enc)
    }

    /** Envoie un état chiffré au relais. */
    private fun envoyerEtat(ack: String?, sonne: Boolean = Ringer.sonne) {
        val ws = socket ?: return
        if (!connecte) return
        ws.send(messageEtat(ack, Build.MODEL, sonne))
        dernierEtatEnvoye = SystemClock.elapsedRealtime()
    }

    // -----------------------------------------------------------------
    // Proximité Bluetooth (v1.1) : mode recherche
    // -----------------------------------------------------------------

    /** Ouvre ou prolonge le mode recherche de 10 min. */
    private fun ouvrirRecherche() {
        rechercheJusqua = SystemClock.elapsedRealtime() + Proximite.DUREE_RECHERCHE_MS
        majProximite()
    }

    /**
     * Démarre, rafraîchit ou arrête balise et serveur GATT selon le mode recherche, le réglage,
     * les permissions, l'état du Bluetooth et la disponibilité de S. Appelé au démarrage, à
     * chaque commande acceptée, au retour du Bluetooth ou du relais, à chaque passage du chien
     * de garde, à la fin du mode recherche et à la frontière de fenêtre.
     * La balise ne démarre qu'une fois le service GATT publié (sinon un chercheur la verrait,
     * se connecterait et ne trouverait pas le service Sonne).
     */
    private fun majProximite() {
        if (!demarre) return
        val maintenant = SystemClock.elapsedRealtime()
        val enRecherche = reglages.balise &&
            Proximite.modeRecherche(maintenant, rechercheJusqua, Ringer.sonne, horsLigneDepuis)
        if (enRecherche != recherche) {
            recherche = enRecherche
            Etat.modeRecherche.value = enRecherche
            Log.i(TAG, if (enRecherche) "Search mode on" else "Search mode off")
            majNotification()
        }
        val voulu = enRecherche && secretPret && Permissions.bluetoothProtege(this) && Permissions.bluetoothAllume(this)
        if (!voulu) {
            arreterProximite()
            return
        }
        // Fin du mode recherche ouvert par une commande : un Handler (processeur éveillé) et le
        // chien de garde (processeur endormi), le premier qui passe referme.
        if (rechercheJusqua > maintenant) {
            principal.removeCallbacks(reevaluerProximite)
            principal.postDelayed(reevaluerProximite, rechercheJusqua - maintenant + 500L)
            programmerGarde(rechercheJusqua - maintenant + 1_000L)
        }
        val g = gatt
        if (g == null || !g.ouvert) {
            val nouveau = ServeurGatt(
                this,
                commandeRecevable = { texte -> commandeRecevable(texte) },
                surCommande = { texte, adresse -> surCommandeGatt(texte, adresse) },
                surPret = { pret -> surGattPret(pret) },
                surDeconnexion = {
                    principal.removeCallbacks(relanceBalise)
                    principal.postDelayed(relanceBalise, 500L)
                },
            )
            if (nouveau.ouvrir()) {
                gatt = nouveau
            } else {
                gatt = null
                programmerEssaiProximite()
                return
            }
        }
        if (gatt?.pret != true) return // la balise attend surGattPret
        balise.assurer(cleBle, System.currentTimeMillis())
        programmerRotation()
    }

    /** Résultat de l'ajout du service GATT (asynchrone). */
    private fun surGattPret(pret: Boolean) {
        if (pret) {
            essaisGatt = 0
        } else {
            gatt = null
            programmerEssaiProximite()
            return
        }
        majProximite()
    }

    /** La pile confirme (ou non) l'émission de la balise : seule source de Etat.balise. */
    private fun surBalise(emet: Boolean) {
        Etat.balise.value = emet
        // Refus (trop d'émetteurs, erreur interne) ou arrêt inattendu alors que la balise est voulue : essai court.
        if (!emet && recherche && gatt?.pret == true) programmerEssaiProximite()
    }

    /**
     * Nouvel essai de serveur GATT ou de balise, avec un délai court et croissant (1, 2, 5, 10,
     * puis 30 s) sous un WakeLock court : juste après STATE_ON, la pile GATT n'est pas toujours prête.
     */
    private fun programmerEssaiProximite() {
        val delai = DELAIS_ESSAI_PROXIMITE_MS[minOf(essaisGatt, DELAIS_ESSAI_PROXIMITE_MS.size - 1)]
        essaisGatt++
        principal.removeCallbacks(reevaluerProximite)
        principal.postDelayed(reevaluerProximite, delai)
        verrouProximite.acquire(delai + 3_000L)
    }

    private fun arreterProximite() {
        if (!::balise.isInitialized) return
        principal.removeCallbacks(reevaluerProximite)
        principal.removeCallbacks(relanceBalise)
        if (verrouProximite.isHeld) verrouProximite.release()
        essaisGatt = 0
        balise.arreter()
        gatt?.fermer()
        gatt = null
        annulerRotation()
        Etat.balise.value = false
    }

    /**
     * Commande écrite par Bluetooth recevable : forme, signature ou authentification,
     * fraîcheur (15 min) et nonce jamais vu, vérifiés avant toute limite de débit et sans rien
     * modifier. Une commande captée sur les ondes et rejouée est donc une écriture invalide,
     * pas une commande qui épuise la limite.
     */
    private fun commandeRecevable(texte: String): Boolean {
        if (!secretPret && !chargerSecret()) return false
        val json = try {
            JSONObject(texte)
        } catch (e: Exception) {
            return false
        }
        val raison = filtre.recevable(cles(), json, System.currentTimeMillis())
        if (raison != null && BuildConfig.DEBUG) Log.i(TAG, "Bluetooth write refused: $raison")
        return raison == null
    }

    /** Commande recevable écrite sur la caractéristique Commande du serveur GATT. */
    private fun surCommandeGatt(texte: String, adresse: String) {
        if (!secretPret && !chargerSecret()) return
        val json = try {
            JSONObject(texte)
        } catch (e: Exception) {
            return
        }
        traiterCommande(json, adresse)
    }

    /**
     * Notifie l'état aux clients GATT qui ont envoyé une commande valide (à `adresse` seulement
     * si elle est donnée). Clair bourré à 320 octets et message écrit sans échappement : 508
     * octets, toujours sous les 512 d'un attribut GATT.
     */
    private fun notifierGatt(adresse: String?, ack: String?, sonne: Boolean = Ringer.sonne) {
        val g = gatt ?: return
        if (!secretPret) return
        g.notifier(adresse) { messageEtat(ack, Build.MODEL, sonne).toByteArray(Charsets.UTF_8) }
    }

    private fun intentionRotation(): PendingIntent =
        PendingIntent.getBroadcast(
            this, 1, Intent(this, ChienDeGarde::class.java).setAction(ACTION_ROTATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Alarme juste après la prochaine frontière de fenêtre (horloge murale), sans permission. */
    private fun programmerRotation() {
        val prochaine = (Crypto.fenetreBle(System.currentTimeMillis()) + 1) * Crypto.DUREE_FENETRE_BLE_MS + 1_000L
        try {
            getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochaine, intentionRotation())
        } catch (e: Exception) {
            Log.w(TAG, "Rotation alarm refused: ${e.message}")
        }
    }

    private fun annulerRotation() {
        try {
            getSystemService(AlarmManager::class.java).cancel(intentionRotation())
        } catch (_: Exception) {
        }
    }

    private fun batterie(): Pair<Int, Boolean> {
        val bm = getSystemService(BatteryManager::class.java)
        val niveau = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val statut = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val enCharge = statut == BatteryManager.BATTERY_STATUS_CHARGING || statut == BatteryManager.BATTERY_STATUS_FULL
        return niveau to enCharge
    }

    private fun notificationVeille(): Notification {
        val ouvrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Canaux.VEILLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.miel))
            .setContentTitle(getString(R.string.veille_titre))
            .setContentText(
                getString(if (connecte) R.string.veille_connecte else R.string.veille_reconnexion) +
                    // Discret mais visible : le propriétaire voit quand la balise est ouverte.
                    if (recherche) " · " + getString(R.string.veille_recherche) else "",
            )
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/**
 * Alarme du chien de garde (non exportée). Prend un verrou de 30 s au plus, le temps de
 * reconnecter ou d'envoyer un battement et d'en attendre la réponse ; le service le relâche
 * dès que la réponse arrive. La rotation de la balise ne prend que quelques millisecondes :
 * son verrou est relâché aussitôt.
 */
class ChienDeGarde : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val verrou = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:garde")
            .apply {
                setReferenceCounted(false)
                acquire(30_000L)
            }
        val service = VeilleService.instance
        when {
            // Démarrage du service : le verrou expire seul (30 s au plus).
            service == null -> VeilleService.demarrer(context, source = Marche.Source.CHIEN_DE_GARDE)
            intent.action == VeilleService.ACTION_ROTATION -> {
                service.rotation()
                if (verrou.isHeld) verrou.release()
            }
            else -> service.chienDeGarde(verrou)
        }
    }
}

/**
 * Relance la veille au démarrage (dès LOCKED_BOOT_COMPLETED, avant le premier déverrouillage),
 * au premier déverrouillage (BOOT_COMPLETED) et après une mise à jour de l'app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> VeilleService.demarrer(context, source = Marche.Source.DEMARRAGE)
        }
    }
}

/** Bouton Stop de la notification de sonnerie. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ringer.arreter(context)
    }
}
