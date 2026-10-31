package dev.sidali.sonne

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** About, What leaves your device, the documents shipped in the app, and Erase everything. */
object APropos {

    private const val TAG = "SonneAPropos"
    const val CONTACT = "contact@sidali.dev"
    const val SITE = "https://sidali.dev"

    /** SHA-256 of the certificate that signed the installed APK, in lowercase hex, read from the system. */
    fun empreinteCertificat(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Signing certificate unreadable: ${e.message}")
        null
    }

    /** First 8 and last 4 characters, as shown in About. */
    fun abreger(empreinte: String): String =
        if (empreinte.length <= 12) empreinte else empreinte.take(8) + "…" + empreinte.takeLast(4)

    /** "2026-10-24" becomes "24 Oct 2026". */
    fun dateLisible(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    } catch (e: Exception) {
        iso
    }

    /** A text file copied from the repository into the app's assets at build time. */
    fun document(context: Context, nom: String): String =
        try {
            context.assets.open(nom).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

    /** Opens a mailto: or https: link. Always a tap from the user, never automatic. */
    fun ouvrir(context: Context, uri: Uri) {
        val intent = if (uri.scheme == "mailto") Intent(Intent.ACTION_SENDTO, uri) else Intent(Intent.ACTION_VIEW, uri)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.apropos_pas_de_mail, CONTACT), Toast.LENGTH_LONG).show()
        }
    }

    fun mail(sujet: String? = null): Uri =
        ("mailto:$CONTACT" + (sujet?.let { "?subject=" + Uri.encode(it) } ?: "")).toUri()

    /**
     * Erases everything this app holds on the phone, then closes it. The next launch is a first launch.
     *
     * Keys first (crypto-shredding): every entry of this app in the Android Keystore is destroyed, so
     * whatever stays in flash memory can no longer be decrypted. Then the system clears the app's data
     * in both storage areas, its runtime permissions, notifications and alarms, and stops the process.
     *
     * The relay has no erase command and writes nothing to disk. Closing the app drops the connection:
     * the relay forgets the chain on its own (waiting commands after 15 min, last seen after 24 h).
     */
    fun effacerTout(context: Context) {
        val app = context.applicationContext ?: context
        runCatching { Ringer.arreter(app) }
        runCatching { app.stopService(Intent(app, VeilleService::class.java)) }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in ks.aliases().toList()) ks.deleteEntry(alias)
        }.onFailure { Log.w(TAG, "Keystore not fully cleared: ${it.message}") }
        val effacee = runCatching { app.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true }.getOrDefault(false)
        if (effacee) return
        // Fallback: delete the files ourselves, in credential and device protected storage.
        for (c in listOf(app, app.createDeviceProtectedStorageContext())) {
            c.dataDir.listFiles()?.forEach { if (it.name != "lib") it.deleteRecursively() }
        }
        Process.killProcess(Process.myPid())
    }
}

// ---------------------------------------------------------------------
// About
// ---------------------------------------------------------------------

@Composable
internal fun EcranAPropos(onSortie: () -> Unit, onConfidentialite: () -> Unit, onNouveautes: () -> Unit) {
    val context = LocalContext.current
    val empreinte = remember { APropos.empreinteCertificat(context) }
    var effacer by remember { mutableStateOf(false) }

    Section(stringResource(R.string.apropos_principes))
    Carte {
        listOf(R.string.apropos_principe_1, R.string.apropos_principe_2, R.string.apropos_principe_3, R.string.apropos_principe_4)
            .forEachIndexed { i, texte ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Coche(22)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(texte), style = MaterialTheme.typography.bodyLarge)
                }
            }
        Separateur()
        LigneLien(stringResource(R.string.sortie_titre), onClick = onSortie)
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.apropos_version_section))
    Carte {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.apropos_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            BoutonCapsule(stringResource(R.string.apropos_nouveautes), onNouveautes)
        }
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.apropos_securite))
    Carte {
        Text(
            stringResource(R.string.apropos_revue, APropos.dateLisible(BuildConfig.SECURITY_REVIEW)),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.apropos_audit), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
        Separateur()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apropos_certificat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                empreinte?.let { APropos.abreger(it) } ?: stringResource(R.string.apropos_certificat_inconnu),
                fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Tokens.colors.secondaryText,
            )
        }
        Spacer(Modifier.height(16.dp))
        val sujet = stringResource(R.string.apropos_sujet_securite)
        BoutonSecondaire(stringResource(R.string.apropos_signaler), onClick = { APropos.ouvrir(context, APropos.mail(sujet)) })
    }

    Spacer(Modifier.height(24.dp))
    Section(stringResource(R.string.apropos_liens))
    Carte {
        LigneLien(stringResource(R.string.apropos_confidentialite), onClick = onConfidentialite)
        Separateur()
        LigneLien(stringResource(R.string.apropos_contact), APropos.CONTACT) { APropos.ouvrir(context, APropos.mail()) }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.apropos_polices), style = MaterialTheme.typography.bodySmall,
        color = Tokens.colors.secondaryText, modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(24.dp))
    Carte {
        BoutonSecondaire(stringResource(R.string.effacer_bouton), onClick = { effacer = true }, couleur = Tokens.colors.danger)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.effacer_aide), style = MaterialTheme.typography.bodySmall, color = Tokens.colors.secondaryText)
    }

    Spacer(Modifier.height(28.dp))
    Text(
        stringResource(R.string.apropos_pied),
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.colors.secondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .pressable { APropos.ouvrir(context, APropos.SITE.toUri()) }
            .padding(vertical = 8.dp),
    )
    Spacer(Modifier.height(16.dp))

    if (effacer) DialogueEffacer(onFermer = { effacer = false })
}

/** Row that opens something: title, optional value, chevron. */
@Composable
private fun LigneLien(titre: String, valeur: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(titre, style = MaterialTheme.typography.titleMedium, color = Tokens.colors.accentInk, modifier = Modifier.weight(1f))
        if (valeur != null) {
            Text(valeur, style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            Spacer(Modifier.width(8.dp))
        }
        Text("›", fontSize = 22.sp, color = Tokens.colors.secondaryText)
    }
}

/** Strong confirmation: the button only works once ERASE is typed. */
@Composable
private fun DialogueEffacer(onFermer: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val mot = stringResource(R.string.effacer_mot)
    var saisie by remember { mutableStateOf("") }
    val pret = saisie.trim() == mot
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.effacer_titre)) },
        text = {
            Column {
                Text(stringResource(R.string.effacer_texte))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = saisie,
                    onValueChange = { saisie = it },
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.buttonRadius),
                    label = { Text(stringResource(R.string.effacer_saisie)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pret, onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                APropos.effacerTout(context)
            }) {
                Text(
                    stringResource(R.string.effacer_valider),
                    color = if (pret) Tokens.colors.danger else Tokens.colors.secondaryText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.annuler)) } },
    )
}

// ---------------------------------------------------------------------
// What leaves your device (same facts as PRIVACY.md)
// ---------------------------------------------------------------------

@Composable
internal fun EcranSortie(reglages: Reglages) {
    val hote = remember { Reglages.hote(reglages.relais) }
    CarteSortie(R.string.sortie_etat_titre, hote, stringResource(R.string.sortie_etat_chiffre), R.string.sortie_etat_texte)
    CarteSortie(R.string.sortie_commandes_titre, hote, stringResource(R.string.sortie_commandes_chiffre), R.string.sortie_commandes_texte)
    Carte {
        Text(stringResource(R.string.sortie_relais_titre), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.sortie_relais_texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.sortie_relais_proxy), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
    }
    Spacer(Modifier.height(12.dp))
    CarteSortie(R.string.sortie_maj_titre, hote, stringResource(R.string.sortie_maj_chiffre), R.string.sortie_maj_texte)
    CarteSortie(
        R.string.sortie_bluetooth_titre, stringResource(R.string.sortie_bluetooth_vers),
        stringResource(R.string.sortie_bluetooth_chiffre), R.string.sortie_bluetooth_texte,
    )
    CarteSortie(R.string.sortie_cle_titre, stringResource(R.string.sortie_cle_vers), stringResource(R.string.sortie_cle_chiffre), R.string.sortie_cle_texte)
    Text(
        stringResource(R.string.sortie_rien), style = MaterialTheme.typography.bodyMedium,
        color = Tokens.colors.secondaryText, modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CarteSortie(titre: Int, vers: String, chiffre: String, texte: Int) {
    Carte {
        Text(stringResource(titre), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.sortie_vers, vers), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sortie_chiffre, chiffre), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(texte), style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
    }
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------------
// Documents shipped in the app: CHANGELOG.md (What's new), PRIVACY.md (Privacy policy)
// ---------------------------------------------------------------------

/** Renders the small Markdown subset used by the repository's documents, one card per "## " section. */
@Composable
internal fun EcranDocument(nom: String) {
    val context = LocalContext.current
    val sections = remember(nom) { Markdown.sections(APropos.document(context, nom)) }
    sections.forEachIndexed { i, s ->
        if (s.titre == null) {
            s.lignes.forEach { LigneDocument(it) }
            Spacer(Modifier.height(16.dp))
        } else {
            Carte {
                Text(Markdown.enLigne(s.titre), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                s.lignes.forEach { LigneDocument(it) }
            }
            if (i < sections.lastIndex) Spacer(Modifier.height(16.dp))
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun LigneDocument(ligne: String) {
    when {
        ligne.isBlank() -> Spacer(Modifier.height(6.dp))
        ligne.startsWith("### ") -> {
            Spacer(Modifier.height(6.dp))
            Text(Markdown.enLigne(ligne.removePrefix("### ")), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
        }
        ligne.startsWith("- ") -> Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = Tokens.colors.secondaryText)
            Text(Markdown.enLigne(ligne.removePrefix("- ")), style = MaterialTheme.typography.bodyMedium)
        }
        else -> Text(Markdown.enLigne(ligne), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
}

/** Just enough Markdown for CHANGELOG.md and PRIVACY.md. */
internal object Markdown {
    class Section(val titre: String?, val lignes: List<String>)

    fun sections(texte: String): List<Section> {
        val resultat = mutableListOf<Section>()
        var titre: String? = null
        var lignes = mutableListOf<String>()
        fun fermer() {
            val propres = lignes.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (titre != null || propres.isNotEmpty()) resultat += Section(titre, propres)
        }
        for (brute in texte.lines()) {
            val ligne = brute.trimEnd()
            when {
                ligne.startsWith("# ") -> Unit // the screen already has the title
                ligne.startsWith("## ") -> {
                    fermer()
                    titre = ligne.removePrefix("## ")
                    lignes = mutableListOf()
                }
                // A wrapped list item or paragraph continues the previous line.
                ligne.startsWith("  ") && lignes.isNotEmpty() && lignes.last().isNotBlank() ->
                    lignes[lignes.lastIndex] = lignes.last() + " " + ligne.trim()
                else -> lignes += ligne
            }
        }
        fermer()
        return resultat
    }

    /** Bold, code and links: the markers are removed, bold stays bold. */
    fun enLigne(texte: String): AnnotatedString {
        val sansLiens = texte.replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1").replace("`", "")
        return buildAnnotatedString {
            sansLiens.split("**").forEachIndexed { i, morceau ->
                if (i % 2 == 1) pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(morceau)
                if (i % 2 == 1) pop()
            }
        }
    }
}
