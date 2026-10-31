package dev.sidali.sonne

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Canaux de notification de l'app. */
object Canaux {
    const val VEILLE = "veille"
    const val SONNERIE = "sonnerie"
    const val MISES_A_JOUR = MiseAJour.CANAL

    fun creer(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(VEILLE, context.getString(R.string.canal_veille), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.canal_veille_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SONNERIE, context.getString(R.string.canal_sonnerie), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.canal_sonnerie_description)
                setSound(null, null) // le son est joué par Ringer sur le flux alarme
                enableVibration(false) // idem pour la vibration
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(MISES_A_JOUR, context.getString(R.string.canal_maj), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.canal_maj_description)
            },
        )
    }
}

/**
 * La sonnerie « même en silencieux » (section Sonnerie du protocole) :
 * flux alarme au volume maximal, Ne pas déranger levé si l'accès est accordé,
 * vibration, lampe torche qui clignote, écran plein au-dessus du verrouillage, arrêt automatique.
 * Toutes les méthodes s'exécutent sur le fil principal.
 * Seul le contexte d'application est gardé (pas de fuite d'activité).
 *
 * Ne pas déranger : une app qui vise Android 15 (targetSdk 35) ou plus ne peut plus modifier
 * le mode global (setInterruptionFilter ne pilote alors qu'une règle propre à l'app, et la
 * restaurer allumerait un mode inconnu de l'utilisateur). Sonne vise donc targetSdk 34 (voir
 * build.gradle.kts) ; dndPilotable() garde le code correct si ce choix change un jour.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object Ringer {

    private const val TAG = "SonneRinger"
    private const val NOTIF_SONNERIE = 2

    private val principal = Handler(Looper.getMainLooper())

    private var lecteur: MediaPlayer? = null
    private var verrou: PowerManager.WakeLock? = null
    private var cameraTorche: String? = null
    private var torcheAllumee = false
    private var torcheEnPause = false
    private var suiviTorche: CameraManager.TorchCallback? = null
    private var contexte: Context? = null

    /**
     * true si setInterruptionFilter agit sur le mode Ne pas déranger global : toujours avant
     * Android 15, et après seulement pour une app qui vise un targetSdk inférieur à 35.
     */
    fun dndPilotable(context: Context): Boolean =
        Build.VERSION.SDK_INT < 35 || context.applicationInfo.targetSdkVersion < 35

    /**
     * true si le mode Ne pas déranger actuel coupe les alarmes : « Aucune interruption », ou
     * un mode prioritaire qui n'autorise pas les alarmes. La sonnerie resterait alors muette.
     */
    fun alarmesBloquees(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                (nm.consolidatedNotificationPolicy.priorityCategories and
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
            else -> false
        }
    }

    /** true si Sonne peut lever le blocage elle-même pendant la sonnerie. */
    fun peutLeverNePasDeranger(context: Context): Boolean =
        dndPilotable(context) &&
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /**
     * Appelé à chaque démarrage ou arrêt effectif de la sonnerie. `accuse` est vrai quand le
     * changement vient d'une commande dont l'état d'accusé, déjà parti, porte la bonne valeur
     * de « ringing » : la veille n'envoie alors pas de second état (un seul état par commande,
     * PROTOCOL.md, « Response: a single status per command »).
     */
    @Volatile
    var surChangement: ((sonne: Boolean, accuse: Boolean) -> Unit)? = null

    val sonne: Boolean get() = Etat.sonne.value

    private val arretAuto = Runnable { contexte?.let { arreter(it) } }

    private val clignotement = object : Runnable {
        override fun run() {
            val ctx = contexte ?: return
            if (!sonne || torcheEnPause) return
            if (torche(ctx, !torcheAllumee)) {
                torcheAllumee = !torcheAllumee
                principal.postDelayed(this, 400)
            } else {
                // Caméra occupée par une autre app : pause jusqu'à ce qu'elle se libère (TorchCallback).
                torcheEnPause = true
            }
        }
    }

    /**
     * Démarre la sonnerie. Si elle sonne déjà, relance seulement le délai d'arrêt automatique,
     * avec la durée de cette commande (ou le réglage) ; la lampe de la sonnerie en cours ne change pas.
     *
     * @param parametres durée et lampe demandées par une commande v3 (null : réglage du téléphone).
     * @param accuse true quand une commande déjà accusée la provoque (pas de second état).
     */
    fun demarrer(
        context: Context,
        parametres: Crypto.ParametresSonnerie = Crypto.ParametresSonnerie(),
        accuse: Boolean = false,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principal.post { demarrer(context, parametres, accuse) }
            return
        }
        val ctx = context.applicationContext
        contexte = ctx
        val reglages = Reglages(ctx)
        // Une durée demandée (60 à 600 s) remplace l'arrêt automatique pour cette sonnerie seulement.
        val dureeMs = parametres.dureeS?.let { it * 1_000L } ?: (reglages.dureeMin * 60_000L)
        principal.removeCallbacks(arretAuto)
        principal.postDelayed(arretAuto, dureeMs)
        // Un seul verrou, non compté : chaque ring réarme son délai au lieu d'en empiler un second.
        val v = verrou ?: ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:sonnerie")
            .apply { setReferenceCounted(false) }
            .also { verrou = it }
        v.acquire(dureeMs + 60_000L)
        if (sonne) return

        Etat.sonne.value = true
        Log.i(TAG, "Ringing started")

        leverNePasDeranger(ctx, reglages)
        monterVolume(ctx, reglages)
        jouer(ctx)
        vibrer(ctx)
        if (parametres.lampe ?: reglages.torche) demarrerTorche(ctx)
        afficher(ctx)

        surChangement?.invoke(true, accuse)
    }

    /**
     * Arrête tout et restaure volume et Ne pas déranger. Sans effet si rien ne sonne.
     *
     * @param accuse true quand une commande stop déjà accusée la provoque (pas de second état).
     */
    fun arreter(context: Context, accuse: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principal.post { arreter(context, accuse) }
            return
        }
        val ctx = context.applicationContext
        principal.removeCallbacks(arretAuto)
        if (!sonne) {
            restaurerSiBesoin(ctx)
            return
        }
        Etat.sonne.value = false
        Log.i(TAG, "Ringing stopped")

        lecteur?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        lecteur = null

        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()

        principal.removeCallbacks(clignotement)
        suiviTorche?.let {
            try {
                ctx.getSystemService(CameraManager::class.java).unregisterTorchCallback(it)
            } catch (_: Exception) {
            }
        }
        suiviTorche = null
        if (torcheAllumee) torche(ctx, false)
        torcheAllumee = false
        torcheEnPause = false

        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_SONNERIE)
        restaurerSiBesoin(ctx)

        verrou?.let { if (it.isHeld) it.release() }
        verrou = null

        surChangement?.invoke(false, accuse)
    }

    /**
     * Restaure le volume d'alarme et le filtre Ne pas déranger mémorisés.
     * Appelé à l'arrêt, et au lancement du service si le processus est mort pendant une sonnerie.
     */
    fun restaurerSiBesoin(context: Context) {
        if (sonne) return
        // Notification de sonnerie orpheline (processus mort pendant une sonnerie) : sans effet si absente.
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_SONNERIE)
        } catch (_: Exception) {
        }
        val reglages = Reglages(context)
        val volume = reglages.volumeARestaurer
        if (volume >= 0) {
            try {
                context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Volume not restored: ${e.message}")
            }
            reglages.volumeARestaurer = -1
        }
        val filtre = reglages.filtreARestaurer
        if (filtre >= 0) {
            val nm = context.getSystemService(NotificationManager::class.java)
            try {
                // On ne touche à rien si l'utilisateur a changé le mode entre temps, ni si l'appel
                // ne pilote plus le mode global (il allumerait une règle Ne pas déranger propre à Sonne).
                if (dndPilotable(context) &&
                    nm.isNotificationPolicyAccessGranted &&
                    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    nm.setInterruptionFilter(filtre)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Do Not Disturb not restored: ${e.message}")
            }
            reglages.filtreARestaurer = -1
        }
    }

    // -----------------------------------------------------------------
    // Étapes de la sonnerie
    // -----------------------------------------------------------------

    private fun leverNePasDeranger(ctx: Context, reglages: Reglages) {
        if (!dndPilotable(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val actuel = nm.currentInterruptionFilter
        if (actuel == NotificationManager.INTERRUPTION_FILTER_ALL ||
            actuel == NotificationManager.INTERRUPTION_FILTER_UNKNOWN ||
            !nm.isNotificationPolicyAccessGranted
        ) {
            return
        }
        try {
            reglages.filtreARestaurer = actuel
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (e: Exception) {
            reglages.filtreARestaurer = -1
            Log.w(TAG, "Do Not Disturb not lifted: ${e.message}")
        }
    }

    private fun monterVolume(ctx: Context, reglages: Reglages) {
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (reglages.volumeARestaurer < 0) {
                reglages.volumeARestaurer = am.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: Exception) {
            Log.w(TAG, "Volume not changed: ${e.message}")
        }
    }

    private val attributsAlarme: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun jouer(ctx: Context) {
        val sources = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in sources) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attributsAlarme)
                mp.setDataSource(ctx, uri)
                mp.isLooping = true
                surErreurRepli(ctx, mp)
                mp.prepare()
                mp.start()
                lecteur = mp
                return
            } catch (e: Exception) {
                Log.w(TAG, "System ringtone unreadable ($uri): ${e.message}")
                mp.release()
            }
        }
        jouerSecours(ctx)
    }

    /**
     * Si la lecture échoue en cours de route (audioserver redémarré, média supprimé ou sur
     * une carte retirée), on passe au son de secours au lieu de rester muet.
     */
    private fun surErreurRepli(ctx: Context, mp: MediaPlayer) {
        mp.setOnErrorListener { p, quoi, extra ->
            Log.w(TAG, "Playback interrupted ($quoi, $extra): falling back to the built-in sound")
            p.release()
            if (lecteur === p) lecteur = null
            principal.post { if (sonne && lecteur == null) jouerSecours(ctx) }
            true
        }
    }

    /** Repli : son généré par l'app, sans fichier sous licence, lisible même avant le déverrouillage. */
    private fun jouerSecours(ctx: Context) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributsAlarme)
            mp.setDataSource(sonDeSecours(ctx).absolutePath)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            lecteur = mp
        } catch (e: Exception) {
            Log.e(TAG, "Built-in sound unreadable: ${e.message}")
            mp.release()
        }
    }

    private fun vibrer(ctx: Context) {
        try {
            val vibreur = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!vibreur.hasVibrator()) return
            val motif = VibrationEffect.createWaveform(longArrayOf(0, 800, 500), 0)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                vibreur.vibrate(motif, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibreur.vibrate(
                    motif,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot vibrate: ${e.message}")
        }
    }

    private fun demarrerTorche(ctx: Context) {
        cameraTorche = cameraTorche ?: try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            cm.cameraIdList.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        }
        val id = cameraTorche ?: return
        torcheEnPause = false
        // Suit la disponibilité de la torche : pause quand une autre app prend la caméra,
        // reprise dès qu'elle la rend.
        val suivi = object : CameraManager.TorchCallback() {
            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId != id) return
                torcheEnPause = true
                principal.removeCallbacks(clignotement)
            }

            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id || !torcheEnPause || !sonne) return
                torcheEnPause = false
                torcheAllumee = enabled
                principal.removeCallbacks(clignotement)
                principal.post(clignotement)
            }
        }
        try {
            ctx.getSystemService(CameraManager::class.java).registerTorchCallback(suivi, principal)
            suiviTorche = suivi
        } catch (e: Exception) {
            Log.w(TAG, "Cannot track the flashlight: ${e.message}")
        }
        principal.post(clignotement)
    }

    /** Allume ou éteint la torche. false si la caméra est occupée ou indisponible. */
    private fun torche(ctx: Context, allumee: Boolean): Boolean {
        val id = cameraTorche ?: return false
        return try {
            ctx.getSystemService(CameraManager::class.java).setTorchMode(id, allumee)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun afficher(ctx: Context) {
        val ouvrir = Intent(ctx, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pleinEcran = PendingIntent.getActivity(
            ctx, 0, ouvrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        Canaux.creer(ctx)
        val notification = Notification.Builder(ctx, Canaux.SONNERIE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ctx.getColor(R.color.miel))
            .setContentTitle(ctx.getString(R.string.sonnerie_titre))
            .setContentText(ctx.getString(R.string.sonnerie_texte))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pleinEcran)
            .setFullScreenIntent(pleinEcran, true)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.stop), stop).build())
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_SONNERIE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refused: ${e.message}")
        }
        // Si l'app est au premier plan (test local), on ouvre directement l'écran.
        // Depuis l'arrière plan, Android ignore cet appel et passe par le fullScreenIntent.
        try {
            ctx.startActivity(ouvrir)
        } catch (_: Exception) {
        }
    }

    /** Fabrique (une fois) un WAV de bips à deux tons dans le cache. */
    private fun sonDeSecours(ctx: Context): File {
        // Cache du stockage par appareil : disponible avant le premier déverrouillage.
        val fichier = File(Stockage.cache(ctx), "sonne-secours.wav")
        if (fichier.exists() && fichier.length() > 44) return fichier
        val frequence = 22_050
        // Motif d'une seconde : 880 Hz, silence, 1320 Hz, silence.
        val motif = listOf(880.0 to 0.25, 0.0 to 0.08, 1320.0 to 0.25, 0.0 to 0.42)
        val echantillons = ArrayList<Short>()
        for ((hz, duree) in motif) {
            val n = (frequence * duree).toInt()
            for (i in 0 until n) {
                val enveloppe = minOf(1.0, i / 200.0, (n - i) / 200.0) // évite les clics
                val v = if (hz == 0.0) 0.0 else sin(2 * PI * hz * i / frequence) * enveloppe * 0.9
                echantillons.add((v * Short.MAX_VALUE).toInt().toShort())
            }
        }
        val donnees = echantillons.size * 2
        val tampon = ByteBuffer.allocate(44 + donnees).order(ByteOrder.LITTLE_ENDIAN)
        tampon.put("RIFF".toByteArray()).putInt(36 + donnees).put("WAVE".toByteArray())
        tampon.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(frequence).putInt(frequence * 2).putShort(2).putShort(16)
        tampon.put("data".toByteArray()).putInt(donnees)
        for (e in echantillons) tampon.putShort(e)
        FileOutputStream(fichier).use { it.write(tampon.array()) }
        return fichier
    }
}
