package dev.sidali.sonne

import android.Manifest
import android.content.ClipboardManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Libellé de chaque niveau de la jauge (0 : perdu, 5 : très chaud). */
private val LIBELLES_NIVEAU = intArrayOf(
    R.string.niveau_0, R.string.niveau_1, R.string.niveau_2, R.string.niveau_3, R.string.niveau_4, R.string.niveau_5,
)

/** Liste « Mes autres téléphones », ou null si elle est illisible. */
@Composable
private fun listeChaines(): List<Chaine>? {
    val context = LocalContext.current
    val version by Chaines.version.collectAsState()
    return remember(version) {
        try {
            Chaines.liste(context)
        } catch (e: Chaines.Illisible) {
            null
        }
    }
}

/** Garde l'écran allumé tant que ce composant est affiché. */
@Composable
private fun EcranAllume() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val fenetre = context.activite()?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ---------------------------------------------------------------------
// Nearby
// ---------------------------------------------------------------------

@Composable
fun EcranNearby(reprise: Int, reglages: Reglages, onChaines: () -> Unit) {
    val context = LocalContext.current
    val chaines = listeChaines()
    var relecture by remember { mutableIntStateOf(0) }
    val cle = reprise + relecture
    val present = remember { Permissions.bluetoothPresent(context) }
    val permissions = remember(cle) { Permissions.bluetoothChercheur(context) }
    var allume by remember { mutableStateOf(Permissions.bluetoothAllume(context)) }
    var dejaDemande by rememberSaveable { mutableStateOf(false) }
    val premierPlan by Etat.premierPlan.collectAsState()
    val chercheur = remember { Chercheur(context) }
    val fraicheur = remember { FraicheurEtats() }
    var choisie by rememberSaveable { mutableStateOf<String?>(null) }
    var bips by remember { mutableStateOf(reglages.bips) }
    var envoiEnCours by remember { mutableStateOf<String?>(null) }
    var dernier by remember { mutableStateOf<Pair<String, Resultat>?>(null) }
    var reveilEnCours by remember { mutableStateOf(false) }
    var reveil by remember { mutableStateOf<Resultat?>(null) }
    val coupureAuto = remember(cle) { Permissions.coupureAutoBluetooth(context) }
    val vues by chercheur.vues.collectAsState()
    val erreurScan by chercheur.erreurScan.collectAsState()
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        dejaDemande = true
        relecture++
    }

    EcranAllume()
    // Le Bluetooth peut être allumé ou éteint depuis le volet rapide sans quitter l'écran.
    LaunchedEffect(Unit) {
        while (true) {
            allume = Permissions.bluetoothAllume(context)
            delay(2_000)
        }
    }

    val liste = chaines.orEmpty()
    val cible = liste.firstOrNull { it.id == choisie } ?: liste.firstOrNull()
    LaunchedEffect(liste) { chercheur.chaines(liste) }
    SideEffect {
        chercheur.cible = cible?.id
        chercheur.bips = bips
    }
    // Scan seulement tant que l'écran est au premier plan.
    val scanPossible = present && permissions && allume && premierPlan && liste.isNotEmpty()
    DisposableEffect(scanPossible) {
        if (scanPossible) chercheur.demarrer()
        onDispose { chercheur.arreter() }
    }
    // Les clés des autres téléphones ne restent pas en mémoire une fois l'écran quitté.
    DisposableEffect(Unit) { onDispose { Chaines.oublierCache() } }
    // La balise du téléphone cherché n'émet qu'en mode recherche : on le lui demande par le
    // relais (ping silencieux), puis toutes les 8 min tant que l'écran est ouvert. Vérification
    // automatique : pas d'envoi si le relais dit le téléphone hors ligne.
    val cibleReveil = cible
    LaunchedEffect(cibleReveil?.id, scanPossible) {
        reveil = null
        if (!scanPossible || cibleReveil == null) return@LaunchedEffect
        while (true) {
            reveilEnCours = true
            EnvoiCommande(context, cibleReveil, "ping", fraicheur, automatique = true) { r ->
                reveilEnCours = false
                reveil = r
            }.lancer(emptyList())
            delay(Proximite.RELANCE_REVEIL_MS)
        }
    }

    Text(stringResource(R.string.nearby_intro))
    Spacer(Modifier.height(16.dp))

    if (chaines == null) {
        Text(stringResource(R.string.chaines_illisibles), color = Tokens.colors.danger)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onChaines, colors = couleursBouton()) { Text(stringResource(R.string.mes_autres_telephones)) }
        return
    }
    if (liste.isEmpty() || cible == null) {
        Carte {
            Text(stringResource(R.string.nearby_aucun_titre), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nearby_aucun_texte))
            Spacer(Modifier.height(8.dp))
            Button(onClick = onChaines, colors = couleursBouton()) { Text(stringResource(R.string.chaine_ajouter)) }
        }
        return
    }

    when {
        !present -> {
            Text(stringResource(R.string.nearby_sans_bluetooth), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
        !permissions -> {
            CarteAlerte(R.string.nearby_permission_titre, R.string.nearby_permission_texte, R.string.perm_autoriser) {
                if (dejaDemande) {
                    Permissions.ouvrir(context, Permissions.reglagesApp(context))
                } else {
                    demande.launch(Permissions.BLUETOOTH_CHERCHEUR)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        !allume -> {
            CarteAlerte(R.string.bt_eteint_titre, R.string.nearby_bt_eteint_texte, R.string.bt_allumer) {
                Permissions.ouvrir(context, Permissions.allumerBluetooth(context))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (liste.size > 1) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            liste.forEach { c ->
                FilterChip(
                    selected = c.id == cible.id,
                    onClick = {
                        choisie = c.id
                        dernier = null
                    },
                    label = { Text(c.nom) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    val vue = vues[cible.id] ?: Chercheur.Vue(0, 0, -1L)
    Carte {
        Text(cible.nom, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Jauge(vue.niveau)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(LIBELLES_NIVEAU[vue.niveau.coerceIn(0, 5)]),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (vue.vueIlYaMs < 0) {
                stringResource(if (scanPossible) R.string.nearby_pas_encore_vu else R.string.nearby_scan_arrete)
            } else {
                stringResource(R.string.nearby_vu_il_y_a, (vue.vueIlYaMs / 1000).toInt(), vue.derniereMesure)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (vue.copie) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.nearby_copie),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.colors.danger,
            )
        }
        val texteReveil = when {
            vue.vueIlYaMs in 0..Proximite.VU_RECEMMENT_MS -> null
            reveilEnCours -> R.string.nearby_reveil_en_cours
            else -> when (val r = reveil) {
                is Resultat.ParRelais -> if (r.livree) R.string.nearby_reveil_envoye else R.string.nearby_reveil_hors_ligne
                is Resultat.Echec, is Resultat.EcriteSansAccuse -> R.string.nearby_reveil_echec
                else -> null
            }
        }
        if (texteReveil != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(texteReveil),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        if (erreurScan != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.nearby_erreur_scan, erreurScan ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.colors.danger,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    val aPortee = vue.vueIlYaMs in 0..Proximite.VU_RECEMMENT_MS
    fun envoyer(cmd: String) {
        envoiEnCours = cmd
        dernier = null
        // Toutes les adresses récentes de la balise, la plus forte d'abord (un clone peut en émettre une).
        val appareils = if (chercheur.aPortee(cible.id)) chercheur.appareils(cible.id) else emptyList()
        if (appareils.isNotEmpty()) chercheur.suspendre()
        EnvoiCommande(context, cible, cmd, fraicheur) { r ->
            chercheur.reprendre()
            envoiEnCours = null
            dernier = cmd to r
        }.lancer(appareils)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = { envoyer("ring") },
            enabled = envoiEnCours == null,
            shape = CircleShape,
            colors = couleursBouton(),
            modifier = Modifier.size(180.dp),
        ) {
            Text(
                stringResource(if (envoiEnCours == "ring") R.string.nearby_envoi else R.string.nearby_ring),
                fontSize = if (envoiEnCours == "ring") 18.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (envoiEnCours == "ring") Tokens.bodyFont else Tokens.titleFont,
                letterSpacing = if (envoiEnCours == "ring") TextUnit.Unspecified else Tokens.titleSpacing,
                textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(if (aPortee) R.string.nearby_chemin_bluetooth else R.string.nearby_chemin_relais),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { envoyer("stop") },
        enabled = envoiEnCours == null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.nearby_stop)) }

    dernier?.let { (cmd, r) ->
        Spacer(Modifier.height(12.dp))
        Text(
            texteResultat(cmd, r),
            style = MaterialTheme.typography.bodyMedium,
            color = if (r is Resultat.Echec) Tokens.colors.danger else Tokens.colors.text,
        )
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.reglages_bips), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.nearby_bips_aide), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = bips, onCheckedChange = {
            bips = it
            reglages.bips = it
        })
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onChaines, colors = couleursBouton()) { Text(stringResource(R.string.mes_autres_telephones)) }
    Text(stringResource(R.string.nearby_limite), style = MaterialTheme.typography.bodySmall)
    if (coupureAuto) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.nearby_bt_coupure),
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.colors.danger,
        )
    }
}

/** Jauge de 5 barres croissantes, remplies jusqu'au niveau courant. */
@Composable
private fun Jauge(niveau: Int) {
    val description = stringResource(R.string.nearby_jauge, niveau)
    val chaleur = Tokens.colors.heat
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 1..Proximite.NIVEAU_MAX) {
            val rempli = i <= niveau
            Box(
                Modifier
                    .width(36.dp)
                    .height((16 + 14 * i).dp)
                    .background(
                        if (rempli) chaleur[i - 1] else Tokens.colors.raised,
                        RoundedCornerShape(6.dp),
                    ),
            )
            if (i < Proximite.NIVEAU_MAX) Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun texteResultat(cmd: String, r: Resultat): String = when (r) {
    is Resultat.ParBluetooth -> stringResource(
        R.string.resultat_bluetooth,
        stringResource(if (r.etat.sonne) R.string.resultat_sonne else R.string.resultat_ne_sonne_pas),
        r.etat.batterie,
        r.etat.app,
    )
    is Resultat.ParRelais -> stringResource(
        when {
            r.livree -> R.string.resultat_relais_livre
            r.enFile -> R.string.resultat_relais_file
            else -> R.string.resultat_relais_hors_ligne
        },
    )
    is Resultat.EcriteSansAccuse -> stringResource(R.string.resultat_sans_accuse)
    is Resultat.Echec -> if (r.code == 429) {
        stringResource(R.string.resultat_trop)
    } else {
        stringResource(if (cmd == "stop") R.string.resultat_echec_stop else R.string.resultat_echec)
    }
}

// ---------------------------------------------------------------------
// Mes autres téléphones
// ---------------------------------------------------------------------

@Composable
fun EcranChaines(reglages: Reglages) {
    val context = LocalContext.current
    val chaines = listeChaines()
    var scanner by rememberSaveable { mutableStateOf(false) }
    var coller by rememberSaveable { mutableStateOf(false) }
    var texteColle by remember { mutableStateOf("") }
    var aConfirmer by remember { mutableStateOf<Saisie?>(null) }
    var nomPropose by remember { mutableStateOf("") }
    var aRenommer by remember { mutableStateOf<Chaine?>(null) }
    var aSupprimer by remember { mutableStateOf<Chaine?>(null) }
    var viderListe by remember { mutableStateOf(false) }

    // Des secrets peuvent être à l'écran (lien collé, 12 mots, caméra) : ni capture ni miniature.
    DisposableEffect(Unit) {
        val fenetre = context.activite()?.window
        fenetre?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Chaines.oublierCache()
        }
    }

    val nomParDefaut = stringResource(R.string.chaine_nom_defaut, (chaines?.size ?: 0) + 1)
    fun analyser(texte: String) {
        try {
            aConfirmer = Saisie.analyser(texte, Bip39.liste(context), BuildConfig.DEBUG)
            nomPropose = nomParDefaut
        } catch (e: IllegalArgumentException) {
            val relais = e.message?.startsWith("Relay refused") == true
            Toast.makeText(
                context,
                if (relais) R.string.chaine_relais_refuse else R.string.chaine_invalide,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val demandeCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanner = true else Toast.makeText(context, R.string.camera_refusee, Toast.LENGTH_LONG).show()
    }

    Text(stringResource(R.string.chaines_intro))
    Spacer(Modifier.height(16.dp))

    if (chaines == null) {
        CarteAlerte(R.string.chaines_illisibles_titre, R.string.chaines_illisibles, R.string.chaines_vider) {
            viderListe = true
        }
    } else if (chaines.isEmpty()) {
        Text(stringResource(R.string.chaines_vide), style = MaterialTheme.typography.bodyMedium)
    } else {
        chaines.forEach { c ->
            Carte {
                Text(c.nom, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.chaine_details, c.id.take(6), Reglages.hote(c.relais)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row {
                    TextButton(onClick = { aRenommer = c }) { Text(stringResource(R.string.renommer)) }
                    TextButton(onClick = { aSupprimer = c }) { Text(stringResource(R.string.retirer)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (chaines != null) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.chaine_ajouter), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (scanner) {
            LecteurQr(
                surTexte = { texte ->
                    scanner = false
                    analyser(texte)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { scanner = false }) { Text(stringResource(R.string.annuler)) }
        } else {
            Button(
                colors = couleursBouton(),
                onClick = {
                    if (Permissions.camera(context)) scanner = true else demandeCamera.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.chaine_scanner)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { coller = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chaine_coller))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chaines_aide), style = MaterialTheme.typography.bodySmall)
    }

    if (coller) {
        AlertDialog(
            onDismissRequest = { coller = false },
            title = { Text(stringResource(R.string.chaine_coller)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = texteColle,
                        onValueChange = { texteColle = it },
                        label = { Text(stringResource(R.string.chaine_coller_aide)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        val presse = context.getSystemService(ClipboardManager::class.java)
                        presse.primaryClip?.getItemAt(0)?.coerceToText(context)?.let { texteColle = it.toString() }
                    }) { Text(stringResource(R.string.coller)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = texteColle.isNotBlank(),
                    onClick = {
                        coller = false
                        analyser(texteColle)
                        texteColle = ""
                    },
                ) { Text(stringResource(R.string.continuer)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    coller = false
                    texteColle = ""
                }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }

    aConfirmer?.let { saisie ->
        val relais = saisie.relais ?: reglages.relais
        AlertDialog(
            onDismissRequest = { aConfirmer = null },
            title = { Text(stringResource(R.string.chaine_confirmer_titre)) },
            text = {
                Column {
                    Text(stringResource(R.string.chaine_confirmer_texte, saisie.id.take(6)))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.chaine_confirmer_relais, Reglages.hote(relais)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nomPropose,
                        onValueChange = { nomPropose = it.take(60) },
                        label = { Text(stringResource(R.string.chaine_nom)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    aConfirmer = null
                    val issue = try {
                        Chaines.ajouter(context, saisie, nomPropose.ifBlank { nomParDefaut }, reglages.relais)
                    } catch (e: Exception) {
                        null
                    }
                    val message = when (issue) {
                        Chaines.Ajout.AJOUTEE -> R.string.chaine_ajoutee
                        Chaines.Ajout.DEJA_LA -> R.string.chaine_deja_la
                        Chaines.Ajout.CE_TELEPHONE -> R.string.chaine_ce_telephone
                        null -> R.string.chaines_illisibles
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.ajouter)) }
            },
            dismissButton = {
                TextButton(onClick = { aConfirmer = null }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }

    aRenommer?.let { c ->
        var nom by remember(c.id) { mutableStateOf(c.nom) }
        AlertDialog(
            onDismissRequest = { aRenommer = null },
            title = { Text(stringResource(R.string.renommer)) },
            text = {
                OutlinedTextField(
                    value = nom,
                    onValueChange = { nom = it.take(60) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = nom.isNotBlank(), onClick = {
                    aRenommer = null
                    try {
                        Chaines.renommer(context, c.id, nom)
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.enregistrer)) }
            },
            dismissButton = {
                TextButton(onClick = { aRenommer = null }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }

    aSupprimer?.let { c ->
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text(stringResource(R.string.chaine_retirer_titre, c.nom)) },
            text = { Text(stringResource(R.string.chaine_retirer_texte)) },
            confirmButton = {
                TextButton(onClick = {
                    aSupprimer = null
                    try {
                        Chaines.supprimer(context, c.id)
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.retirer)) }
            },
            dismissButton = {
                TextButton(onClick = { aSupprimer = null }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }

    if (viderListe) {
        AlertDialog(
            onDismissRequest = { viderListe = false },
            title = { Text(stringResource(R.string.chaines_vider)) },
            text = { Text(stringResource(R.string.chaines_vider_texte)) },
            confirmButton = {
                TextButton(onClick = {
                    viderListe = false
                    Chaines.effacerTout(context)
                }) { Text(stringResource(R.string.chaines_vider)) }
            },
            dismissButton = {
                TextButton(onClick = { viderListe = false }) { Text(stringResource(R.string.annuler)) }
            },
        )
    }
}
