package dev.sidali.sonne

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Les réglages dont Sonne a besoin, et les écrans système qui les accordent.
 * L'accès Ne pas déranger n'est demandé que là où il sert (voir Ringer.dndPilotable).
 */
object Permissions {

    /** Sur Android 13 et plus, reflète aussi la permission POST_NOTIFICATIONS. */
    fun notifications(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** La permission POST_NOTIFICATIONS se demande par dialogue seulement depuis Android 13. */
    val notificationsParDialogue: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun nePasDeranger(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun batterie(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun pleinEcran(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

    /** L'accès Ne pas déranger sert-il à quelque chose sur cet appareil ? */
    fun nePasDerangerUtile(context: Context): Boolean = Ringer.dndPilotable(context)

    // -----------------------------------------------------------------
    // Bluetooth (Nearby)
    // -----------------------------------------------------------------

    /** Téléphone protégé : émettre la balise et ouvrir le serveur GATT. */
    val BLUETOOTH_PROTEGE = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

    /** Chercheur : scanner (sans localisation) et se connecter au serveur GATT. */
    val BLUETOOTH_CHERCHEUR = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun accordees(context: Context, permissions: Array<String>): Boolean =
        permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothProtege(context: Context): Boolean = accordees(context, BLUETOOTH_PROTEGE)

    fun bluetoothChercheur(context: Context): Boolean = accordees(context, BLUETOOTH_CHERCHEUR)

    fun camera(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** L'appareil sait-il faire du Bluetooth Low Energy ? */
    fun bluetoothPresent(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            context.getSystemService(BluetoothManager::class.java)?.adapter != null

    /** Le Bluetooth est-il allumé ? (lecture sans permission depuis Android 12) */
    fun bluetoothAllume(context: Context): Boolean =
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }

    /**
     * GrapheneOS : « Turn off Bluetooth automatically » coupe le Bluetooth quand aucun appareil
     * n'est connecté ; ni la balise ni le scan ne comptent comme connexion. Réglage
     * Settings.Global « bluetooth_off_timeout » (délai en ms, 0 : désactivé). false s'il est
     * absent (autre système), désactivé ou illisible : depuis Android 12, un réglage caché non
     * marqué @Readable peut être refusé à une app (SecurityException).
     */
    fun coupureAutoBluetooth(context: Context): Boolean =
        try {
            Settings.Global.getLong(context.contentResolver, "bluetooth_off_timeout", 0L) > 0L
        } catch (_: Exception) {
            false
        }

    fun reglagesBluetooth(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    /** Demande d'allumage du Bluetooth (dialogue système, demande BLUETOOTH_CONNECT), sinon ses réglages. */
    fun allumerBluetooth(context: Context): Intent =
        if (accordees(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }

    /** Fiche de l'app dans les réglages (permission refusée deux fois : plus de dialogue). */
    fun reglagesApp(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

    /** Nombre de réglages encore manquants. */
    fun manquantes(context: Context): Int =
        listOfNotNull(
            notifications(context),
            if (nePasDerangerUtile(context)) nePasDeranger(context) else null,
            batterie(context),
            pleinEcran(context),
            if (Reglages(context).balise && bluetoothPresent(context)) bluetoothProtege(context) else null,
        ).count { !it }

    fun reglagesNotifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun reglagesNePasDeranger(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Réglages des modes Ne pas déranger (pour y autoriser les alarmes). */
    fun reglagesModesNePasDeranger(): Intent = Intent("android.settings.ZEN_MODE_SETTINGS")

    /** Demande directe d'exclusion de l'optimisation de batterie (dialogue système). */
    @SuppressLint("BatteryLife")
    fun reglagesBatterie(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))

    fun reglagesPleinEcran(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
        } else {
            reglagesNotifications(context)
        }

    // -----------------------------------------------------------------
    // Écran Autorisations (voir Autorisations pour la logique)
    // -----------------------------------------------------------------

    /** Tout le groupe « Appareils à proximité » : balise, serveur GATT et recherche. */
    val BLUETOOTH_TOUS = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    )

    fun appareilsProches(context: Context): Boolean = accordees(context, BLUETOOTH_TOUS)

    fun cameraPresente(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun installation(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** État actuel d'un accès, relu à chaque retour dans l'app. */
    fun accorde(context: Context, acces: Acces): Boolean = when (acces) {
        Acces.NOTIFICATIONS -> notifications(context)
        Acces.PLEIN_ECRAN -> pleinEcran(context)
        Acces.NE_PAS_DERANGER -> nePasDeranger(context)
        Acces.BATTERIE -> batterie(context)
        Acces.APPAREILS_PROCHES -> appareilsProches(context)
        Acces.CAMERA -> camera(context)
        Acces.INSTALLATION -> installation(context)
    }

    /**
     * L'écran système exact pour activer ou retirer un accès. Pour retirer l'exclusion de
     * l'optimisation de batterie : la fiche de l'app (« Utilisation de la batterie ») depuis
     * Android 12, la liste des optimisations avant.
     */
    fun reglages(context: Context, acces: Acces, retirer: Boolean): Intent = when (acces) {
        Acces.NOTIFICATIONS -> reglagesNotifications(context)
        Acces.PLEIN_ECRAN -> reglagesPleinEcran(context)
        Acces.NE_PAS_DERANGER -> reglagesNePasDeranger()
        Acces.BATTERIE ->
            if (!retirer) reglagesBatterie(context)
            else if (Build.VERSION.SDK_INT >= 31) reglagesApp(context)
            else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Acces.APPAREILS_PROCHES, Acces.CAMERA -> reglagesApp(context)
        Acces.INSTALLATION ->
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
    }

    /** Ouvre un écran de réglages, avec repli sur la fiche de l'app si l'écran demandé n'existe pas. */
    fun ouvrir(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
                )
            } catch (_: Exception) {
            }
        }
    }
}
