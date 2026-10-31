package dev.sidali.sonne

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** État de la mise à jour intégrée, affiché par le bandeau de l'accueil. */
sealed class EtatMaj {
    data object Aucune : EtatMaj()
    data class Disponible(val annonce: Annonce) : EtatMaj()

    /** L'autorisation « Installer des applis inconnues » manque : le bandeau l'explique. */
    data class AutorisationRequise(val annonce: Annonce) : EtatMaj()
    data class Telechargement(val annonce: Annonce, val pourcent: Int) : EtatMaj()
    data class Verification(val annonce: Annonce) : EtatMaj()

    /** Session PackageInstaller envoyée : l'écran système de confirmation est à l'utilisateur. */
    data class Installation(val annonce: Annonce) : EtatMaj()
    data class Echec(val annonce: Annonce, val raison: RaisonEchec) : EtatMaj()
}

/**
 * Mise à jour intégrée, sans magasin (comme les versions hors Play de Signal ou Telegram).
 * Voir docs/PROTOCOL.md, section « Update (app 1.2) ».
 *
 * - Vérification : GET <relais>/update/android.json une fois par jour (chien de garde de la
 *   veille) et à l'ouverture de l'app, si le réglage est actif. versionCode distant plus grand
 *   que celui installé : notification (canal dédié, priorité normale) et bandeau sur l'accueil.
 * - Au tap : téléchargement dans le cache, SHA-256 annoncé, puis paquet, versionCode et
 *   certificat de signature de l'APK identiques à ceux de l'app installée et à certSha256.
 *   Sinon refus et suppression du fichier.
 * - Installation par PackageInstaller avec USER_ACTION_REQUIRED : l'écran système de
 *   confirmation est toujours montré, rien ne s'installe sans l'utilisateur.
 */
object MiseAJour {

    private const val TAG = "SonneMaj"
    const val CANAL = "mises_a_jour"
    private const val NOTIF_MAJ = 3

    /** Ouvre l'app et lance le téléchargement (tap sur la notification). */
    const val ACTION_INSTALLER = "dev.sidali.sonne.action.MISE_A_JOUR"
    const val ACTION_RESULTAT = "dev.sidali.sonne.action.RESULTAT_INSTALLATION"

    private const val INTERVALLE_MS = 24L * 60 * 60 * 1000
    private const val REESSAI_MS = 60L * 60 * 1000
    private const val OUVERTURE_MIN_MS = 60_000L

    private val executeur = Executors.newSingleThreadExecutor { r -> Thread(r, "sonne-maj").apply { isDaemon = true } }
    private val principal = Handler(Looper.getMainLooper())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var verificationEnCours = false

    @Volatile
    private var derniereOuverture = 0L

    private class Refus(val raison: RaisonEchec, message: String) : Exception(message)

    /** Recharge l'annonce connue (après un redémarrage du processus) et nettoie le cache. */
    fun initialiser(context: Context) {
        val reglages = Reglages(context)
        val annonce = lireAnnonce(reglages.majAnnonce)
        if (Etat.miseAJour.value is EtatMaj.Aucune && reglages.majAuto && annonce != null &&
            VerifMiseAJour.plusRecente(annonce, BuildConfig.VERSION_CODE.toLong())
        ) {
            Etat.miseAJour.value = EtatMaj.Disponible(annonce)
        }
        if (Etat.miseAJour.value is EtatMaj.Aucune) {
            executeur.execute { dossier(context).listFiles()?.forEach { it.delete() } }
        }
    }

    /** À l'ouverture de l'app : vérifie (au plus une fois par minute, rotation de l'écran comprise). */
    fun verifierOuverture(context: Context) {
        if (!Reglages(context).majAuto) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - derniereOuverture < OUVERTURE_MIN_MS) return
        derniereOuverture = maintenant
        verifier(context)
    }

    /** Chien de garde de la veille : une vérification par jour, nouvel essai une heure après un échec. */
    fun verifierSiDu(context: Context) {
        val reglages = Reglages(context)
        if (!reglages.majAuto) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - reglages.majDerniereVerif < INTERVALLE_MS) return
        if (maintenant - reglages.majDernierEssai < REESSAI_MS) return
        verifier(context)
    }

    /**
     * Interroge le relais. `surFin` (fil principal) reçoit true si une mise à jour est
     * disponible, false si l'app est à jour, null en cas d'échec.
     */
    fun verifier(context: Context, surFin: ((Boolean?) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        if (verificationEnCours) {
            surFin?.let { principal.post { it(null) } }
            return
        }
        verificationEnCours = true
        val reglages = Reglages(app)
        reglages.majDernierEssai = System.currentTimeMillis()
        // Appelé aussi depuis l'alarme du chien de garde : le processeur reste éveillé le temps de la requête.
        val verrou = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonne:maj")
            .apply {
                setReferenceCounted(false)
                acquire(30_000L)
            }
        executeur.execute {
            var resultat: Boolean? = null
            try {
                resultat = interroger(app, reglages)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot check for updates: ${e.message}")
            } finally {
                verificationEnCours = false
                if (verrou.isHeld) verrou.release()
            }
            surFin?.let { f -> principal.post { f(resultat) } }
        }
    }

    private fun interroger(context: Context, reglages: Reglages): Boolean {
        val url = reglages.relais.trimEnd('/') + "/update/android.json"
        val requete = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val texte = client.newCall(requete).execute().use { r ->
            if (r.code == 404) {
                reglages.majDerniereVerif = System.currentTimeMillis()
                return false
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val corps = r.body ?: throw IOException("empty response")
            if (corps.contentLength() > 64 * 1024) throw IOException("announcement too large")
            corps.string()
        }
        val annonce = lireAnnonce(texte) ?: throw IOException("invalid announcement")
        reglages.majDerniereVerif = System.currentTimeMillis()
        reglages.majAnnonce = texte
        val disponible = VerifMiseAJour.plusRecente(annonce, BuildConfig.VERSION_CODE.toLong())
        Log.i(TAG, "Announced ${annonce.version} (${annonce.versionCode}), installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (!disponible) {
            principal.post { if (Etat.miseAJour.value is EtatMaj.Disponible) Etat.miseAJour.value = EtatMaj.Aucune }
            return false
        }
        principal.post {
            when (Etat.miseAJour.value) {
                // Ne pas interrompre un téléchargement ou une installation en cours.
                is EtatMaj.Aucune, is EtatMaj.Disponible, is EtatMaj.Echec ->
                    if (reglages.majAuto) Etat.miseAJour.value = EtatMaj.Disponible(annonce)
                else -> Unit
            }
        }
        if (reglages.majNotifiee < annonce.versionCode) {
            notifier(context, context.getString(R.string.maj_notif_texte, annonce.version))
            reglages.majNotifiee = annonce.versionCode
        }
        return true
    }

    /** Lit et valide l'annonce JSON ; null si elle est absente ou mal formée. */
    fun lireAnnonce(texte: String?): Annonce? {
        if (texte.isNullOrBlank()) return null
        return try {
            val j = JSONObject(texte)
            VerifMiseAJour.annonce(
                version = j.optString("version").takeIf { it.isNotEmpty() },
                versionCode = if (j.has("versionCode")) j.optLong("versionCode", -1L) else null,
                url = j.optString("url").takeIf { it.isNotEmpty() },
                sha256 = j.optString("sha256").takeIf { it.isNotEmpty() },
                certSha256 = j.optString("certSha256").takeIf { it.isNotEmpty() },
                notes = j.optString("notes"),
                autoriserHttp = BuildConfig.DEBUG,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Réglage désactivé : plus de bandeau ni de notification (sauf opération en cours). */
    fun desactiver(context: Context) {
        annulerNotification(context)
        if (Etat.miseAJour.value is EtatMaj.Disponible || Etat.miseAJour.value is EtatMaj.Echec ||
            Etat.miseAJour.value is EtatMaj.AutorisationRequise
        ) {
            Etat.miseAJour.value = EtatMaj.Aucune
        }
    }

    /** L'app peut demander l'installation d'un paquet (« Installer des applis inconnues »). */
    fun peutInstaller(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Écran système pour autoriser Sonne à installer des applis. */
    fun intentionAutorisation(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Bouton du bandeau ou tap sur la notification : télécharge, vérifie et installe. */
    fun lancer(context: Context) {
        val app = context.applicationContext ?: context
        val annonce = annonceCourante(app) ?: return
        when (Etat.miseAJour.value) {
            is EtatMaj.Telechargement, is EtatMaj.Verification, is EtatMaj.Installation -> return
            else -> Unit
        }
        annulerNotification(app)
        if (!peutInstaller(app)) {
            Etat.miseAJour.value = EtatMaj.AutorisationRequise(annonce)
            return
        }
        Etat.miseAJour.value = EtatMaj.Telechargement(annonce, 0)
        executeur.execute {
            var fichier: File? = null
            try {
                fichier = telecharger(app, annonce)
                Etat.miseAJour.value = EtatMaj.Verification(annonce)
                verifierApk(app, annonce, fichier)
                Log.i(TAG, "APK ${annonce.version} verified (SHA-256 and certificate), installing")
                installer(app, annonce, fichier)
            } catch (e: Refus) {
                Log.w(TAG, "Update refused (${e.raison}): ${e.message}")
                fichier?.delete()
                Etat.miseAJour.value = EtatMaj.Echec(annonce, e.raison)
            } catch (e: Exception) {
                Log.w(TAG, "Update failed: ${e.message}")
                fichier?.delete()
                val raison = if (e is IOException) RaisonEchec.RESEAU else RaisonEchec.INSTALLATION
                Etat.miseAJour.value = EtatMaj.Echec(annonce, raison)
            }
        }
    }

    private fun annonceCourante(context: Context): Annonce? =
        when (val e = Etat.miseAJour.value) {
            is EtatMaj.Disponible -> e.annonce
            is EtatMaj.AutorisationRequise -> e.annonce
            is EtatMaj.Echec -> e.annonce
            is EtatMaj.Telechargement -> e.annonce
            is EtatMaj.Verification -> e.annonce
            is EtatMaj.Installation -> e.annonce
            EtatMaj.Aucune -> lireAnnonce(Reglages(context).majAnnonce)
                ?.takeIf { VerifMiseAJour.plusRecente(it, BuildConfig.VERSION_CODE.toLong()) }
        }

    private fun dossier(context: Context): File = File(context.cacheDir, "maj")

    private fun telecharger(context: Context, annonce: Annonce): File {
        val dossier = dossier(context).apply { mkdirs() }
        dossier.listFiles()?.forEach { it.delete() }
        val fichier = File(dossier, "sonne-${annonce.versionCode}.apk")
        val requete = Request.Builder().url(annonce.url).build()
        client.newCall(requete).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val corps = r.body ?: throw IOException("empty response")
            val total = corps.contentLength()
            if (total > VerifMiseAJour.TAILLE_MAX) throw Refus(RaisonEchec.TAILLE, "APK too large: $total bytes")
            try {
                corps.byteStream().use { entree ->
                    fichier.outputStream().use { sortie ->
                        val tampon = ByteArray(64 * 1024)
                        var lus = 0L
                        var dernier = -1
                        while (true) {
                            val n = entree.read(tampon)
                            if (n < 0) break
                            lus += n
                            if (lus > VerifMiseAJour.TAILLE_MAX) throw Refus(RaisonEchec.TAILLE, "APK too large")
                            sortie.write(tampon, 0, n)
                            if (total > 0) {
                                val p = (lus * 100 / total).toInt().coerceIn(0, 100)
                                if (p != dernier) {
                                    dernier = p
                                    Etat.miseAJour.value = EtatMaj.Telechargement(annonce, p)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (dossier.usableSpace < 1024L * 1024) throw Refus(RaisonEchec.STOCKAGE, e.message ?: "storage full")
                throw e
            }
        }
        return fichier
    }

    /**
     * SHA-256 annoncé, puis paquet et versionCode attendus, puis certificat de signature :
     * identique à celui de l'app installée et à certSha256 de l'annonce.
     */
    private fun verifierApk(context: Context, annonce: Annonce, fichier: File) {
        if (!VerifMiseAJour.verifierSha256(fichier, annonce.sha256)) {
            throw Refus(RaisonEchec.SHA256, "SHA-256 differs from the announcement")
        }
        val pm = context.packageManager
        val apk = infosArchive(pm, fichier) ?: throw Refus(RaisonEchec.PAQUET, "APK unreadable")
        if (apk.packageName != context.packageName) throw Refus(RaisonEchec.PAQUET, "other package: ${apk.packageName}")
        if (apk.longVersionCode != annonce.versionCode) {
            throw Refus(RaisonEchec.PAQUET, "versionCode ${apk.longVersionCode} instead of ${annonce.versionCode}")
        }
        val installe = infosInstallees(pm, context.packageName)
        val certsApk = apk.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        val certsInstalles = installe.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        VerifMiseAJour.verifierCertificats(certsApk, certsInstalles, annonce.certSha256)?.let {
            throw Refus(it, "signing certificate differs from the installed app or the announcement")
        }
    }

    @Suppress("DEPRECATION")
    private fun infosArchive(pm: PackageManager, fichier: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(fichier.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageArchiveInfo(fichier.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun infosInstallees(pm: PackageManager, paquet: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(paquet, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageInfo(paquet, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun installer(context: Context, annonce: Annonce, fichier: File) {
        val installateur = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(fichier.length())
            // Toujours l'écran système de confirmation : rien ne s'installe sans l'utilisateur.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val idSession = installateur.createSession(params)
        try {
            installateur.openSession(idSession).use { session ->
                session.openWrite("sonne.apk", 0, fichier.length()).use { sortie ->
                    fichier.inputStream().use { it.copyTo(sortie) }
                    session.fsync(sortie)
                }
                val retour = Intent(context, InstallationRecue::class.java).setAction(ACTION_RESULTAT)
                // Mutable : PackageInstaller y ajoute le statut et l'écran de confirmation (intention explicite).
                val pi = PendingIntent.getBroadcast(
                    context, idSession, retour,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                Etat.miseAJour.value = EtatMaj.Installation(annonce)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try {
                installateur.abandonSession(idSession)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** Statut renvoyé par PackageInstaller (InstallationRecue). */
    internal fun surResultat(context: Context, intent: Intent) {
        val statut = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val annonce = annonceCourante(context)
        when (statut) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                Log.i(TAG, "User confirmation requested")
                if (confirmation != null) {
                    // L'activité la lance dès qu'elle est au premier plan (pas de lancement depuis l'arrière-plan).
                    Etat.confirmationInstallation.value = confirmation
                    if (!Etat.premierPlan.value && annonce != null) {
                        notifier(context, context.getString(R.string.maj_notif_confirmer, annonce.version))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed")
                Etat.miseAJour.value = EtatMaj.Aucune
                dossier(context).listFiles()?.forEach { it.delete() }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Installation cancelled by the user")
                dossier(context).listFiles()?.forEach { it.delete() }
                Etat.miseAJour.value = annonce?.let { EtatMaj.Disponible(it) } ?: EtatMaj.Aucune
            }
            else -> {
                Log.w(TAG, "Installation refused ($statut): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                dossier(context).listFiles()?.forEach { it.delete() }
                Etat.miseAJour.value = annonce?.let { EtatMaj.Echec(it, RaisonEchec.INSTALLATION) } ?: EtatMaj.Aucune
            }
        }
    }

    private fun notifier(context: Context, texte: String) {
        val ouvrir = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_INSTALLER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.miel))
            .setContentTitle(context.getString(R.string.maj_notif_titre))
            .setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_MAJ, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun annulerNotification(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_MAJ)
        } catch (_: Exception) {
        }
    }
}

/** Résultat d'une session PackageInstaller (non exportée, PendingIntent explicite). */
class InstallationRecue : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MiseAJour.ACTION_RESULTAT) MiseAJour.surResultat(context, intent)
    }
}
