package dev.sidali.sonne

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.coroutines.cancellation.CancellationException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

private enum class Ecran { PERMISSIONS, AUTORISATIONS, ACCUEIL, APPAIRAGE, REGLAGES, NEARBY, CHAINES, APROPOS, SORTIE, CONFIDENTIALITE, NOUVEAUTES }

/** Full screen pages, with a back button and predictive back, and where back leads. */
private val PLEIN_ECRAN = mapOf(
    Ecran.NEARBY to Ecran.ACCUEIL,
    Ecran.CHAINES to Ecran.NEARBY,
    Ecran.APROPOS to Ecran.REGLAGES,
    Ecran.SORTIE to Ecran.APROPOS,
    Ecran.CONFIDENTIALITE to Ecran.APROPOS,
    Ecran.NOUVEAUTES to Ecran.APROPOS,
    // Back from Permissions leads to where it was opened from (see origineAutorisations).
    Ecran.AUTORISATIONS to Ecran.REGLAGES,
)

/** Racine de l'interface. `reprise` change à chaque retour au premier plan. */
@Composable
fun AppSonne(reprise: Int) {
    val context = LocalContext.current
    val reglages = remember { Reglages(context) }
    var ecran by rememberSaveable { mutableStateOf(if (reglages.permissionsVues) Ecran.ACCUEIL else Ecran.PERMISSIONS) }
    var origineAutorisations by rememberSaveable { mutableStateOf(Ecran.REGLAGES) }
    val ouvrirAutorisations = { depuis: Ecran -> origineAutorisations = depuis; ecran = Ecran.AUTORISATIONS }

    // Mise à jour : l'écran système de confirmation est lancé par l'activité, au premier plan.
    val confirmation by Etat.confirmationInstallation.collectAsState()
    val auPremierPlan by Etat.premierPlan.collectAsState()
    LaunchedEffect(confirmation, auPremierPlan) {
        val c = confirmation ?: return@LaunchedEffect
        if (!auPremierPlan) return@LaunchedEffect
        Etat.confirmationInstallation.value = null
        try {
            context.startActivity(c)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.maj_echec_installation, Toast.LENGTH_LONG).show()
        }
    }

    if (ecran == Ecran.PERMISSIONS) {
        EcranPermissions(reprise) {
            val premierLancement = !reglages.permissionsVues
            reglages.permissionsVues = true
            // Premier lancement : l'étape décisive est d'appairer un navigateur, on y va directement.
            ecran = if (premierLancement || !reglages.appairageVu) Ecran.APPAIRAGE else Ecran.ACCUEIL
        }
        return
    }

    // Nearby, My other phones and About: full screen, back by the button or the system gesture.
    if (ecran in PLEIN_ECRAN) {
        val retour = { ecran = if (ecran == Ecran.AUTORISATIONS) origineAutorisations else PLEIN_ECRAN.getValue(ecran) }
        // Predictive back: the screen follows the swipe (shrinks toward the opposite edge, corners round),
        // then leaves, or springs back if the swipe is cancelled.
        val apercu = remember(ecran) { Animatable(0f) }
        var bord by remember(ecran) { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
        val portee = rememberCoroutineScope()
        PredictiveBackHandler { evenements ->
            try {
                evenements.collect { e -> bord = e.swipeEdge; apercu.snapTo(e.progress) }
                retour()
            } catch (e: CancellationException) {
                portee.launch { apercu.animateTo(0f, spring(Tokens.pressDamping, Tokens.pressStiffness)) }
                throw e
            }
        }
        val decalage = with(LocalDensity.current) { Tokens.backShift.toPx() }
        Box(Modifier.fillMaxSize().background(Tokens.colors.raised)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = apercu.value
                    val echelle = 1f - (1f - Tokens.backScale) * p
                    scaleX = echelle; scaleY = echelle
                    translationX = decalage * p * if (bord == BackEventCompat.EDGE_LEFT) 1f else -1f
                    shape = RoundedCornerShape(Tokens.cardRadius * p * 2)
                    clip = p > 0f
                }
                .background(Tokens.colors.page)
                .safeDrawingPadding()
                // Each page opens at its top.
                .verticalScroll(remember(ecran) { ScrollState(0) })
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "‹ " + stringResource(R.string.retour), color = Tokens.colors.accentInk, fontSize = 17.sp,
                modifier = Modifier.pressable(onClick = retour).padding(vertical = 8.dp),
            )
            Text(
                stringResource(
                    when (ecran) {
                        Ecran.NEARBY -> R.string.nearby_titre
                        Ecran.CHAINES -> R.string.mes_autres_telephones
                        Ecran.APROPOS -> R.string.apropos_titre
                        Ecran.SORTIE -> R.string.sortie_titre
                        Ecran.CONFIDENTIALITE -> R.string.apropos_confidentialite
                        Ecran.AUTORISATIONS -> R.string.permissions_titre
                        else -> R.string.apropos_nouveautes
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(16.dp))
            when (ecran) {
                Ecran.NEARBY -> EcranNearby(reprise, reglages, onChaines = { ecran = Ecran.CHAINES })
                Ecran.CHAINES -> EcranChaines(reglages)
                Ecran.APROPOS -> EcranAPropos(
                    onSortie = { ecran = Ecran.SORTIE },
                    onConfidentialite = { ecran = Ecran.CONFIDENTIALITE },
                    onNouveautes = { ecran = Ecran.NOUVEAUTES },
                )
                Ecran.SORTIE -> EcranSortie(reglages)
                Ecran.CONFIDENTIALITE -> EcranDocument("PRIVACY.md")
                Ecran.AUTORISATIONS -> EcranAutorisations(reprise)
                else -> EcranDocument("CHANGELOG.md")
            }
        }
        }
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            when (ecran) {
                Ecran.ACCUEIL -> EcranAccueil(
                    reprise,
                    reglages,
                    onPermissions = { ouvrirAutorisations(Ecran.ACCUEIL) },
                    onAppairage = { ecran = Ecran.APPAIRAGE },
                    onNearby = { ecran = Ecran.NEARBY },
                )
                Ecran.APPAIRAGE -> EcranAppairage(reglages)
                Ecran.REGLAGES -> EcranReglages(
                    reprise,
                    reglages,
                    onAutorisations = { ouvrirAutorisations(Ecran.REGLAGES) },
                    onAPropos = { ecran = Ecran.APROPOS },
                )
                else -> Unit
            }
        }
        BarreOnglets(ecran) { ecran = it }
    }
}

/** iOS-style bottom tab bar: three tabs, accent on the selected one. */
@Composable
private fun BarreOnglets(courant: Ecran, onChoix: (Ecran) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Tokens.colors.hairline))
        Row(
            Modifier.fillMaxWidth().background(Tokens.colors.card.copy(alpha = 0.96f)).padding(vertical = 6.dp),
        ) {
            listOf(Ecran.ACCUEIL to R.string.onglet_accueil, Ecran.APPAIRAGE to R.string.cle_titre, Ecran.REGLAGES to R.string.onglet_reglages).forEach { (e, titre) ->
                val actif = courant == e
                val couleur = if (actif) Tokens.colors.accentInk else Tokens.colors.secondaryText
                Column(
                    Modifier.weight(1f).pressable(role = Role.Tab) { onChoix(e) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconeOnglet(e, couleur)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(titre), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = couleur)
                }
            }
        }
    }
}

@Composable
private fun IconeOnglet(e: Ecran, couleur: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.minDimension / 24f
        val trait = Stroke(Tokens.strokeWidth * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (e) {
            Ecran.ACCUEIL -> dessinerCloche(couleur, size.minDimension) // the app's bell
            Ecran.APPAIRAGE -> { // key
                drawCircle(couleur, 4.2f * u, Offset(8 * u, 12 * u), style = trait)
                drawLine(couleur, Offset(12.2f * u, 12 * u), Offset(21 * u, 12 * u), Tokens.strokeWidth * u, StrokeCap.Round)
                drawLine(couleur, Offset(18 * u, 12 * u), Offset(18 * u, 15.5f * u), Tokens.strokeWidth * u, StrokeCap.Round)
                drawLine(couleur, Offset(21 * u, 12 * u), Offset(21 * u, 15 * u), Tokens.strokeWidth * u, StrokeCap.Round)
            }
            else -> { // gear: circle + spokes
                drawCircle(couleur, 3.2f * u, Offset(12 * u, 12 * u), style = trait)
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    val c = Offset(12 * u, 12 * u)
                    drawLine(couleur, c + Offset((6.2 * u * kotlin.math.cos(ang)).toFloat(), (6.2 * u * kotlin.math.sin(ang)).toFloat()),
                        c + Offset((8.6 * u * kotlin.math.cos(ang)).toFloat(), (8.6 * u * kotlin.math.sin(ang)).toFloat()), 2.4f * u, StrokeCap.Round)
                }
                drawCircle(couleur, 6.2f * u, Offset(12 * u, 12 * u), style = trait)
            }
        }
    }
}

// ---------------------------------------------------------------------
// Accueil
// ---------------------------------------------------------------------

@Composable
private fun EcranAccueil(
    reprise: Int,
    reglages: Reglages,
    onPermissions: () -> Unit,
    onAppairage: () -> Unit,
    onNearby: () -> Unit,
) {
    val context = LocalContext.current
    val actif by Etat.actif.collectAsState()
    val connecte by Etat.connecte.collectAsState()
    val derniere by Etat.derniereConnexion.collectAsState()
    val sonne by Etat.sonne.collectAsState()
    val perdu by Etat.secretPerdu.collectAsState()
    val baliseActive by Etat.balise.collectAsState()
    val enRecherche by Etat.modeRecherche.collectAsState()
    val coupureAuto = remember(reprise) { Permissions.coupureAutoBluetooth(context) }
    val manquantes = remember(reprise) { Permissions.manquantes(context) }
    val baliseVoulue = remember(reprise) { reglages.balise && Permissions.bluetoothPresent(context) }
    val bluetoothEteint = remember(reprise) { !Permissions.bluetoothAllume(context) }
    val appairageVu = remember(reprise) { reglages.appairageVu }
    // Ne pas déranger couperait les alarmes et Sonne ne peut pas le lever elle-même.
    val alarmesBloquees = remember(reprise) {
        Ringer.alarmesBloquees(context) && !Ringer.peutLeverNePasDeranger(context)
    }
    var confirmerNouvelleCle by remember { mutableStateOf(false) }

    // Refreshes "X min ago" and the battery every 30 s.
    var horloge by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            horloge = System.currentTimeMillis()
        }
    }
    val batterie = remember(reprise, horloge) {
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    val cleVerifiee = remember(reprise) { reglages.cleVerifiee }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        Row(
            Modifier.background(Tokens.colors.card, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pastille(if (actif && connecte) Tokens.colors.success else Tokens.colors.secondaryText, vivant = actif && connecte)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when {
                        !actif -> R.string.etat_court_eteint
                        connecte -> R.string.etat_court_connecte
                        else -> R.string.etat_court_hors_ligne
                    },
                ),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.accueil_sous_titre), style = MaterialTheme.typography.bodyLarge, color = Tokens.colors.secondaryText)

    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        HeroTelephone(actif = (actif && connecte) || sonne, sonne = sonne, modifier = Modifier.size(220.dp))
    }

    InterrupteurPrincipal(actif) { Marche.basculer(context, it) }
    Spacer(Modifier.height(16.dp))

    BoutonPrincipal(
        stringResource(if (sonne) R.string.arreter_sonnerie else R.string.tester_sonnerie),
        onClick = { if (sonne) Ringer.arreter(context) else Ringer.demarrer(context) },
        couleur = if (sonne) Tokens.colors.danger else Tokens.colors.accent,
        surCouleur = if (sonne) Tokens.colors.onDanger else Tokens.colors.onAccent,
    )
    Spacer(Modifier.height(10.dp))
    BoutonSecondaire(stringResource(R.string.accueil_nearby), onClick = onNearby)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.tester_explication), style = MaterialTheme.typography.bodySmall,
        color = Tokens.colors.secondaryText, modifier = Modifier.padding(horizontal = 4.dp),
    )

    // Things that need attention, most important first.
    if (perdu) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.secret_perdu_titre, R.string.secret_perdu_texte, R.string.secret_perdu_bouton) { confirmerNouvelleCle = true }
    } else if (!appairageVu) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.etape_suivante_titre, R.string.etape_suivante_texte, R.string.etape_suivante_bouton, onAppairage)
    } else if (!cleVerifiee) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.cle_rappel_titre, R.string.cle_rappel_texte, R.string.cle_rappel_bouton, onAppairage)
    }
    if (manquantes > 0) {
        Spacer(Modifier.height(20.dp))
        Carte {
            Text(pluralStringResource(R.plurals.permissions_manquantes, manquantes, manquantes), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.permissions_rappel), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            Spacer(Modifier.height(14.dp))
            BoutonSecondaire(stringResource(R.string.voir_autorisations), onPermissions)
        }
    }
    if (baliseVoulue && bluetoothEteint) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.bt_eteint_titre, R.string.bt_eteint_texte, R.string.bt_allumer) {
            Permissions.ouvrir(context, Permissions.allumerBluetooth(context))
        }
    }
    if (baliseVoulue && coupureAuto) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.bt_coupure_titre, R.string.bt_coupure_texte, R.string.bt_coupure_bouton) {
            Permissions.ouvrir(context, Permissions.reglagesBluetooth())
        }
    }
    if (alarmesBloquees) {
        Spacer(Modifier.height(20.dp))
        CarteAlerte(R.string.alarmes_bloquees_titre, R.string.alarmes_bloquees_texte, R.string.alarmes_bloquees_bouton) {
            Permissions.ouvrir(context, Permissions.reglagesModesNePasDeranger())
        }
    }

    Spacer(Modifier.height(20.dp))
    CarteMiseAJour(reprise)

    Section(stringResource(R.string.etat_titre))
    Carte {
        val texteDerniere = if (derniere <= 0L) stringResource(R.string.etat_jamais)
        else DateUtils.getRelativeTimeSpanString(derniere, horloge, DateUtils.MINUTE_IN_MILLIS).toString()
        if (!connecte) {
            Text(stringResource(R.string.etat_derniere_connexion, texteDerniere), style = MaterialTheme.typography.bodyMedium)
            Separateur()
        }
        Text(stringResource(R.string.etat_batterie, batterie), style = MaterialTheme.typography.bodyMedium)
        Separateur()
        Text(stringResource(R.string.etat_relais, Reglages(context).relais.removePrefix("https://")), style = MaterialTheme.typography.bodyMedium)
        Separateur()
        Text(
            stringResource(
                when {
                    !baliseVoulue -> R.string.etat_balise_desactivee
                    baliseActive -> R.string.etat_balise_active
                    enRecherche -> R.string.etat_balise_inactive
                    else -> R.string.etat_balise_veille
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(16.dp))

    if (confirmerNouvelleCle) {
        AlertDialog(
            onDismissRequest = { confirmerNouvelleCle = false },
            title = { Text(stringResource(R.string.secret_perdu_bouton)) },
            text = { Text(stringResource(R.string.nouvelle_chaine_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmerNouvelleCle = false
                    try {
                        Coffre.nouvelleChaine(context)
                        VeilleService.demarrer(context, VeilleService.ACTION_RECONNECTER)
                        onAppairage()
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.secret_indisponible, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.nouvelle_chaine_valider)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmerNouvelleCle = false }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }
}

// ---------------------------------------------------------------------
// Appairage
// ---------------------------------------------------------------------

/** Données affichées par l'écran Appairage, tirées de S. */
private class Appairage(val lien: String, val mots: List<String>, val id: String)

@Composable
private fun EcranAppairage(reglages: Reglages) {
    val context = LocalContext.current
    val version by Etat.versionSecret.collectAsState()
    var confirmer by remember { mutableStateOf(false) }

    // La clé est à l'écran : ni capture, ni miniature dans les apps récentes, ni partage d'écran.
    DisposableEffect(Unit) {
        val fenetre = context.activite()?.window
        // Debug builds stay capturable so the screen can be checked on an emulator.
        if (!BuildConfig.DEBUG) fenetre?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { reglages.appairageVu = true }

    // Recalculé quand la clé change. null si elle est illisible pour l'instant ou perdue.
    val base = reglages.relais
    val donnees = remember(version, base) {
        try {
            val s = Coffre.secret(context)
            val resultat = Appairage(Crypto.versLien(s, base), Crypto.versMots(s, Bip39.liste(context)), Crypto.deriveId(s))
            s.fill(0)
            resultat
        } catch (e: Exception) {
            null
        }
    }
    if (donnees == null) {
        Text(stringResource(R.string.secret_indisponible))
        return
    }
    val lien = donnees.lien
    val mots = donnees.mots
    val qr = remember(lien) { qrBitmap(lien, 600) }
    val hote = Reglages.hote(base)
    val personnalise = base.trimEnd('/') != Reglages.RELAIS_PAR_DEFAUT

    val scope = rememberCoroutineScope()
    var sauvegardee by remember(version) { mutableStateOf(reglages.cleSauvegardee) }
    var verifiee by remember(version) { mutableStateOf(reglages.cleVerifiee) }
    var quiz by remember { mutableStateOf(false) }
    val idCourt = donnees.id.take(6)
    val kit = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && Cle.ecrireKit(context, uri, mots, qr, idCourt, hote)) {
            reglages.cleSauvegardee = true; sauvegardee = true
            Toast.makeText(context, R.string.cle_kit_enregistre, Toast.LENGTH_SHORT).show()
            if (!verifiee) quiz = true
        }
    }

    Text(stringResource(R.string.cle_titre), style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.cle_sous_titre), style = MaterialTheme.typography.bodyLarge, color = Tokens.colors.secondaryText)
    Spacer(Modifier.height(20.dp))

    // 1. Backup first: it is what people forget.
    Carte {
        if (verifiee) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Coche()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.cle_verifiee), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cle_verifiee_texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text(stringResource(R.string.cle_sauvegarde_titre), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.cle_sauvegarde_texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            Spacer(Modifier.height(16.dp))
        }
        BoutonPrincipal(stringResource(R.string.cle_enregistrer_gestionnaire), onClick = {
            val act = context.activite() ?: return@BoutonPrincipal
            scope.launch {
                if (Cle.enregistrerDansGestionnaire(act, idCourt, mots)) {
                    reglages.cleSauvegardee = true; sauvegardee = true
                    Toast.makeText(context, R.string.cle_enregistree, Toast.LENGTH_SHORT).show()
                    if (!verifiee) quiz = true
                } else {
                    Toast.makeText(context, R.string.cle_echec_gestionnaire, Toast.LENGTH_LONG).show()
                }
            }
        })
        Spacer(Modifier.height(10.dp))
        BoutonSecondaire(stringResource(R.string.cle_kit), onClick = { kit.launch("Sonne-$idCourt.pdf") })
        if (!verifiee) {
            Spacer(Modifier.height(10.dp))
            BoutonSecondaire(stringResource(R.string.cle_verifier), onClick = { quiz = true }, couleur = Tokens.colors.success)
        }
    }

    // 2. The words.
    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.cle_mots_titre))
    Carte {
        mots.chunked(3).forEachIndexed { ligne, trio ->
            Row(Modifier.fillMaxWidth()) {
                trio.forEachIndexed { colonne, mot ->
                    Row(Modifier.weight(1f).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${ligne * 3 + colonne + 1}", fontSize = 12.sp, color = Tokens.colors.secondaryText, modifier = Modifier.width(20.dp))
                        Text(mot, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row { BoutonCapsule(stringResource(R.string.cle_copier_mots), { copier(context, mots.joinToString(" ")) }) }
    }

    // 3. Pair a browser.
    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.cle_qr_titre))
    Carte {
        Text(stringResource(R.string.cle_qr_texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.appairage_qr),
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(230.dp).background(Tokens.colors.qrPaper, RoundedCornerShape(16.dp)).padding(10.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.appairage_hote, hote), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
        if (personnalise) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.appairage_relais_perso, hote), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.warning)
        }
        Spacer(Modifier.height(10.dp))
        Row { BoutonCapsule(stringResource(R.string.cle_copier_lien), { copier(context, lien) }) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.appairage_historique), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
    }

    // 4. Advanced.
    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.cle_avance))
    Carte {
        Text(stringResource(R.string.appairage_secret), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
        if (reglages.balise) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.appairage_balise), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.appairage_identifiant, idCourt), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Tokens.colors.secondaryText)
        Spacer(Modifier.height(16.dp))
        BoutonSecondaire(stringResource(R.string.nouvelle_chaine), onClick = { confirmer = true }, couleur = Tokens.colors.danger)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.nouvelle_chaine_explication), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
    }
    Spacer(Modifier.height(24.dp))

    if (quiz) {
        QuizCle(mots, onSucces = {
            reglages.cleVerifiee = true; reglages.cleSauvegardee = true
            verifiee = true; sauvegardee = true; quiz = false
        }, onFermer = { quiz = false })
    }

    if (confirmer) {
        AlertDialog(
            onDismissRequest = { confirmer = false },
            title = { Text(stringResource(R.string.nouvelle_chaine_titre)) },
            text = { Text(stringResource(R.string.nouvelle_chaine_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmer = false
                    try {
                        Coffre.nouvelleChaine(context)
                        VeilleService.demarrer(context, VeilleService.ACTION_RECONNECTER)
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.secret_indisponible, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.nouvelle_chaine_valider)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmer = false }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }
}

/** L'activité qui porte ce contexte (Compose donne en général l'activité elle-même). */
internal fun Context.activite(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Dessine le QR code du lien dans un Bitmap (ZXing core, sans dépendance Android). */
private fun qrBitmap(texte: String, taille: Int): Bitmap {
    val matrice = QRCodeWriter().encode(
        texte,
        BarcodeFormat.QR_CODE,
        taille,
        taille,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val l = matrice.width
    val h = matrice.height
    val pixels = IntArray(l * h) { i -> if (matrice.get(i % l, i / l)) Encre.noir else Encre.blanc }
    return Bitmap.createBitmap(pixels, l, h, Bitmap.Config.ARGB_8888)
}

/** Copie un secret dans le presse-papiers en le marquant sensible (masqué dans l'aperçu). */
private fun copier(context: Context, texte: String) {
    val clip = ClipData.newPlainText("Sonne", texte)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, R.string.copie, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------
// Réglages
// ---------------------------------------------------------------------

@Composable
private fun EcranReglages(reprise: Int, reglages: Reglages, onAutorisations: () -> Unit, onAPropos: () -> Unit) {
    val context = LocalContext.current
    val appareilsProches = remember(reprise) { Permissions.bluetoothProtege(context) }
    var relais by rememberSaveable { mutableStateOf(reglages.relais) }
    var relaisEnregistre by remember { mutableStateOf(reglages.relais) }
    var duree by remember { mutableFloatStateOf(reglages.dureeMin.toFloat()) }
    var torche by remember { mutableStateOf(reglages.torche) }
    var balise by remember { mutableStateOf(reglages.balise) }
    var bips by remember { mutableStateOf(reglages.bips) }
    var majAuto by remember { mutableStateOf(reglages.majAuto) }
    var verificationManuelle by remember { mutableStateOf(false) }
    val valide = Reglages.relaisValide(relais)
    // Activer la balise sans les permissions Bluetooth : on les demande dans la foulée.
    val demandeBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        VeilleService.demarrer(context, VeilleService.ACTION_PROXIMITE)
    }

    Text(stringResource(R.string.onglet_reglages), style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(20.dp))

    Section(stringResource(R.string.reglages_section_sonnerie))
    Carte {
        Text(stringResource(R.string.reglages_duree, duree.toInt()), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = duree,
            onValueChange = { duree = it },
            onValueChangeFinished = { reglages.dureeMin = duree.toInt() },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = Tokens.colors.accentInk, activeTrackColor = Tokens.colors.accentInk,
                inactiveTrackColor = Tokens.colors.raised,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
        Text(stringResource(R.string.reglages_duree_aide), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
        Separateur()
        LigneInterrupteur(R.string.reglages_torche, stringResource(R.string.reglages_torche_aide), torche) {
            torche = it
            reglages.torche = it
        }
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.reglages_section_proximite))
    Carte {
        LigneInterrupteur(
            R.string.reglages_balise,
            stringResource(R.string.reglages_balise_aide) + " " + stringResource(R.string.reglages_balise_nom),
            balise,
        ) {
            balise = it
            reglages.balise = it
            if (it && !Permissions.bluetoothProtege(context)) {
                demandeBluetooth.launch(Permissions.BLUETOOTH_PROTEGE)
            } else {
                VeilleService.demarrer(context, VeilleService.ACTION_PROXIMITE)
            }
        }
        // The beacon is on but Nearby devices was turned off: say so calmly, and where to turn it back on.
        if (balise && !appareilsProches && Permissions.bluetoothPresent(context)) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reglages_balise_coupee),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.colors.secondaryText,
                modifier = Modifier.pressable(onClick = onAutorisations),
            )
        }
        Separateur()
        LigneInterrupteur(R.string.reglages_bips, stringResource(R.string.reglages_bips_aide), bips) {
            bips = it
            reglages.bips = it
        }
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.reglages_section_maj))
    Carte {
        LigneInterrupteur(R.string.reglages_maj, stringResource(R.string.reglages_maj_aide, Reglages.hote(relaisEnregistre)), majAuto) {
            majAuto = it
            reglages.majAuto = it
            if (it) MiseAJour.verifier(context) else MiseAJour.desactiver(context)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            BoutonCapsule(stringResource(R.string.maj_verifier), {
                if (verificationManuelle) return@BoutonCapsule
                verificationManuelle = true
                MiseAJour.verifier(context) { disponible ->
                    verificationManuelle = false
                    val texte = when (disponible) {
                        true -> R.string.maj_verifier_disponible
                        false -> R.string.maj_verifier_a_jour
                        null -> R.string.maj_verifier_echec
                    }
                    Toast.makeText(context, texte, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.reglages_relais))
    Carte {
        OutlinedTextField(
            value = relais,
            onValueChange = { relais = it },
            singleLine = true,
            isError = !valide,
            shape = RoundedCornerShape(Tokens.buttonRadius),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(if (valide) R.string.reglages_relais_aide else R.string.reglages_relais_invalide)) },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (valide && relais.trim().trimEnd('/') != relaisEnregistre) {
                BoutonCapsule(stringResource(R.string.enregistrer), {
                    reglages.relais = relais
                    relaisEnregistre = reglages.relais
                    relais = reglages.relais
                    VeilleService.demarrer(context, VeilleService.ACTION_RECONNECTER)
                })
            }
            BoutonCapsule(stringResource(R.string.par_defaut), { relais = Reglages.RELAIS_PAR_DEFAUT })
        }
    }

    Spacer(Modifier.height(24.dp))
    Carte {
        Row(Modifier.fillMaxWidth().pressable(onClick = onAutorisations), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.permissions_titre), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.reglages_autorisations_aide), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 22.sp, color = Tokens.colors.secondaryText)
        }
        Separateur()
        Row(Modifier.fillMaxWidth().pressable(onClick = onAPropos), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apropos_ligne), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 22.sp, color = Tokens.colors.secondaryText)
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * Interrupteur principal : toute la ligne se touche, TalkBack lit le titre, la phrase et l'état
 * (activé ou désactivé). Même geste dans les deux sens, aucune confirmation.
 */
@Composable
private fun InterrupteurPrincipal(actif: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    Carte(
        Modifier.clip(RoundedCornerShape(Tokens.cardRadius)).toggleable(value = actif, role = Role.Switch) {
            view.performHapticFeedback(
                when {
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CLOCK_TICK
                    it -> HapticFeedbackConstants.TOGGLE_ON
                    else -> HapticFeedbackConstants.TOGGLE_OFF
                },
            )
            onChange(it)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (actif) R.string.marche_on else R.string.marche_off), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(if (actif) R.string.marche_on_aide else R.string.marche_off_aide),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = actif,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Tokens.colors.success, checkedThumbColor = Tokens.colors.onSuccess,
                    checkedIconColor = Tokens.colors.success, checkedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun LigneInterrupteur(titre: Int, aide: String, valeur: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(titre), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(aide, style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
        }
        Spacer(Modifier.width(12.dp))
        Interrupteur(valeur, onChange)
    }
}

// ---------------------------------------------------------------------
// Autorisations (premier lancement, puis depuis l'accueil)
// ---------------------------------------------------------------------

@Composable
private fun EcranPermissions(reprise: Int, onContinuer: () -> Unit) {
    val context = LocalContext.current
    val reglages = remember { Reglages(context) }
    val baliseVoulue = remember(reprise) { reglages.balise && Permissions.bluetoothPresent(context) }
    val coupureAuto = remember(reprise) { Permissions.coupureAutoBluetooth(context) }
    val liste = remember(baliseVoulue) {
        Autorisations.premierLancement(Build.VERSION.SDK_INT, Permissions.nePasDerangerUtile(context), baliseVoulue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        HeroTelephone(actif = true, sonne = false, modifier = Modifier.size(200.dp))
        Text(stringResource(R.string.permissions_titre), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.permissions_accroche), style = MaterialTheme.typography.bodyLarge,
            color = Tokens.colors.secondaryText, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Carte { ListeAcces(reprise, liste) }
        if (baliseVoulue && coupureAuto) {
            Spacer(Modifier.height(16.dp))
            CarteAlerte(R.string.bt_coupure_titre, R.string.bt_coupure_texte, R.string.bt_coupure_bouton) {
                Permissions.ouvrir(context, Permissions.reglagesBluetooth())
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.permissions_intro), style = MaterialTheme.typography.bodySmall,
            color = Tokens.colors.secondaryText, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        BoutonPrincipal(stringResource(R.string.continuer), onContinuer)
        Spacer(Modifier.height(24.dp))
    }
}

/** Every permission Sonne uses, at any time from Settings or the home screen: why, state, on and off. */
@Composable
private fun EcranAutorisations(reprise: Int) {
    val context = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    val essentiels = remember { Autorisations.essentiels(sdk, Permissions.nePasDerangerUtile(context)) }
    val optionnels = remember {
        Autorisations.optionnels(Permissions.bluetoothPresent(context), Permissions.cameraPresente(context))
    }
    val baliseVoulue = remember(reprise) { Reglages(context).balise && Permissions.bluetoothPresent(context) }
    val coupureAuto = remember(reprise) { Permissions.coupureAutoBluetooth(context) }

    Text(stringResource(R.string.autorisations_intro), style = MaterialTheme.typography.bodyLarge, color = Tokens.colors.secondaryText)
    Spacer(Modifier.height(20.dp))
    Section(stringResource(R.string.autorisations_section_sonner))
    Carte { ListeAcces(reprise, essentiels) }
    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.autorisations_section_options))
    Carte { ListeAcces(reprise, optionnels) }
    if (baliseVoulue && coupureAuto) {
        Spacer(Modifier.height(16.dp))
        CarteAlerte(R.string.bt_coupure_titre, R.string.bt_coupure_texte, R.string.bt_coupure_bouton) {
            Permissions.ouvrir(context, Permissions.reglagesBluetooth())
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.autorisations_toujours), style = MaterialTheme.typography.bodySmall,
        color = Tokens.colors.secondaryText, modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(16.dp))
}

/**
 * One row per permission. State is read again on every return to the app ([reprise]) and after
 * each system dialog. A tap asks once by dialog, then opens Settings; when on, it opens the exact
 * Settings screen where it can be turned off.
 */
@Composable
private fun ListeAcces(reprise: Int, liste: List<Acces>) {
    val context = LocalContext.current
    var relecture by remember { mutableIntStateOf(0) }
    val etats = remember(reprise, relecture, liste) { liste.associateWith { Permissions.accorde(context, it) } }
    // Refused once this session: no second dialog, the next tap opens Settings.
    var refuses by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var enCours by rememberSaveable { mutableStateOf<String?>(null) }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultat ->
        val acces = enCours?.let { Acces.valueOf(it) }
        enCours = null
        if (acces != null && (resultat.isEmpty() || !resultat.values.all { it })) refuses = refuses + acces.name
        if (acces == Acces.APPAREILS_PROCHES) VeilleService.demarrer(context, VeilleService.ACTION_PROXIMITE)
        relecture++
    }

    liste.forEachIndexed { i, acces ->
        if (i > 0) Separateur()
        val accorde = etats[acces] == true
        val geste = Autorisations.geste(acces, accorde, Build.VERSION.SDK_INT, acces.name in refuses)
        LigneAcces(acces, accorde, geste) {
            when (geste) {
                Geste.DEMANDER -> when (acces) {
                    Acces.NOTIFICATIONS -> { enCours = acces.name; demande.launch(arrayOf("android.permission.POST_NOTIFICATIONS")) }
                    Acces.APPAREILS_PROCHES -> { enCours = acces.name; demande.launch(Permissions.BLUETOOTH_TOUS) }
                    Acces.CAMERA -> { enCours = acces.name; demande.launch(arrayOf(Manifest.permission.CAMERA)) }
                    // Battery: the system dialog is a screen of its own.
                    else -> Permissions.ouvrir(context, Permissions.reglages(context, acces, retirer = false))
                }
                Geste.ACTIVER_DANS_REGLAGES -> Permissions.ouvrir(context, Permissions.reglages(context, acces, retirer = false))
                Geste.RETIRER_DANS_REGLAGES -> Permissions.ouvrir(context, Permissions.reglages(context, acces, retirer = true))
            }
        }
    }
}

/** Title, why it is needed, what is paused while it is off, and the action, on or off, with the same weight. */
@Composable
private fun LigneAcces(acces: Acces, accorde: Boolean, geste: Geste, onClick: () -> Unit) {
    val (titre, pourquoi, coupe) = when (acces) {
        Acces.NOTIFICATIONS -> Triple(R.string.perm_notifs_titre, R.string.perm_notifs_texte, R.string.perm_notifs_coupe)
        Acces.PLEIN_ECRAN -> Triple(R.string.perm_plein_ecran_titre, R.string.perm_plein_ecran_texte, R.string.perm_plein_ecran_coupe)
        Acces.NE_PAS_DERANGER -> Triple(R.string.perm_dnd_titre, R.string.perm_dnd_texte, R.string.perm_dnd_coupe)
        Acces.BATTERIE -> Triple(R.string.perm_batterie_titre, R.string.perm_batterie_texte, R.string.perm_batterie_coupe)
        Acces.APPAREILS_PROCHES -> Triple(R.string.perm_bluetooth_titre, R.string.perm_bluetooth_texte, R.string.perm_bluetooth_coupe)
        Acces.CAMERA -> Triple(R.string.perm_camera_titre, R.string.perm_camera_texte, R.string.perm_camera_coupe)
        Acces.INSTALLATION -> Triple(R.string.perm_installation_titre, R.string.perm_installation_texte, R.string.perm_installation_coupe)
    }
    val action = stringResource(
        when (geste) {
            Geste.DEMANDER -> R.string.perm_autoriser
            Geste.ACTIVER_DANS_REGLAGES -> R.string.perm_activer_reglages
            Geste.RETIRER_DANS_REGLAGES -> R.string.perm_retirer_reglages
        },
    )
    val etat = stringResource(if (accorde) R.string.perm_etat_actif else R.string.perm_etat_coupe)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = etat }
            .pressable(onClickLabel = action, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(titre), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(pourquoi), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            if (!accorde) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(coupe), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(action + " ›", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Tokens.colors.accentInk)
        }
        Spacer(Modifier.width(12.dp))
        if (accorde) Coche() else CercleVide()
    }
}

/** Off state: an empty ring where the check mark would be. */
@Composable
private fun CercleVide(taille: Int = 28) {
    val couleur = Tokens.colors.secondaryText
    Canvas(Modifier.size(taille.dp)) {
        val epaisseur = size.minDimension / 14f
        drawCircle(couleur, radius = size.minDimension / 2 - epaisseur / 2, style = Stroke(epaisseur))
    }
}

@Composable
internal fun Separateur() {
    Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Tokens.colors.hairline))
}

/** Carte d'alerte (couleurs d'erreur) avec un bouton d'action. */
/** Bandeau de mise à jour de l'accueil : disponible, autorisation, téléchargement, vérification, échec. */
@Composable
private fun CarteMiseAJour(reprise: Int) {
    val context = LocalContext.current
    val etat by Etat.miseAJour.collectAsState()
    val peutInstaller = remember(reprise, etat) { MiseAJour.peutInstaller(context) }
    val annonce = when (val e = etat) {
        EtatMaj.Aucune -> return
        is EtatMaj.Disponible -> e.annonce
        is EtatMaj.AutorisationRequise -> e.annonce
        is EtatMaj.Telechargement -> e.annonce
        is EtatMaj.Verification -> e.annonce
        is EtatMaj.Installation -> e.annonce
        is EtatMaj.Echec -> e.annonce
    }
    val echec = etat is EtatMaj.Echec || (etat is EtatMaj.AutorisationRequise && !peutInstaller)
    val fond = if (echec) Tokens.colors.dangerSoft else Tokens.colors.notice
    val texteCouleur = if (echec) Tokens.colors.onDangerSoft else Tokens.colors.onNotice
    Card(colors = CardDefaults.cardColors(containerColor = fond), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.maj_titre, annonce.version),
                style = MaterialTheme.typography.titleMedium,
                color = texteCouleur,
            )
            Spacer(Modifier.height(4.dp))
            when (val e = etat) {
                is EtatMaj.Disponible, is EtatMaj.AutorisationRequise -> {
                    Text(stringResource(R.string.maj_texte, BuildConfig.VERSION_NAME), color = texteCouleur)
                    if (annonce.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(annonce.notes, style = MaterialTheme.typography.bodySmall, color = texteCouleur)
                    }
                    if (!peutInstaller) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.maj_autorisation), color = texteCouleur)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { Permissions.ouvrir(context, MiseAJour.intentionAutorisation(context)) }, colors = couleursBouton()) {
                            Text(stringResource(R.string.maj_autoriser))
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { MiseAJour.lancer(context) }, colors = couleursBouton()) { Text(stringResource(R.string.maj_installer)) }
                    }
                }
                is EtatMaj.Telechargement -> {
                    Text(stringResource(R.string.maj_telechargement, e.pourcent), color = texteCouleur)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { e.pourcent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is EtatMaj.Verification -> Text(stringResource(R.string.maj_verification), color = texteCouleur)
                is EtatMaj.Installation -> Text(stringResource(R.string.maj_installation), color = texteCouleur)
                is EtatMaj.Echec -> {
                    Text(
                        stringResource(
                            when (e.raison) {
                                RaisonEchec.RESEAU -> R.string.maj_echec_reseau
                                RaisonEchec.TAILLE -> R.string.maj_echec_taille
                                RaisonEchec.SHA256 -> R.string.maj_echec_sha256
                                RaisonEchec.CERTIFICAT -> R.string.maj_echec_certificat
                                RaisonEchec.PAQUET -> R.string.maj_echec_paquet
                                RaisonEchec.STOCKAGE -> R.string.maj_echec_stockage
                                RaisonEchec.INSTALLATION -> R.string.maj_echec_installation
                            },
                        ),
                        color = texteCouleur,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { MiseAJour.lancer(context) }, colors = couleursBouton()) { Text(stringResource(R.string.maj_reessayer)) }
                }
                EtatMaj.Aucune -> Unit
            }
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun CarteAlerte(titre: Int, texte: Int, bouton: Int, onClick: () -> Unit) {
    Carte {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Tokens.colors.warning, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(titre), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
        Spacer(Modifier.height(14.dp))
        BoutonSecondaire(stringResource(bouton), onClick, couleur = Tokens.colors.warning)
    }
}
