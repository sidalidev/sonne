package dev.sidali.sonne

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import java.text.DateFormat
import java.util.Date

/** Everything about keeping the 12-word key safe: password manager, rescue kit, check. */
object Cle {

    /**
     * Hands the key to the user's password manager (Proton Pass, Bitwarden...) through Android's
     * Credential Manager. The words are the "password", the entry name says what it is.
     */
    suspend fun enregistrerDansGestionnaire(activite: Activity, idCourt: String, mots: List<String>): Boolean = try {
        CredentialManager.create(activite).createCredential(
            activite,
            CreatePasswordRequest(id = "Sonne $idCourt", password = mots.joinToString(" ")),
        )
        true
    } catch (e: Exception) {
        false
    }

    /** One A4 page: title, date, QR code, the 12 words, and what to do with them. */
    fun ecrireKit(context: Context, uri: Uri, mots: List<String>, qr: Bitmap, idCourt: String, hote: String): Boolean = try {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val titre = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val gris = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Encre.gris }
        val mot = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.MONOSPACE }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Encre.grisClair; typeface = Typeface.MONOSPACE }
        c.drawText(context.getString(R.string.cle_kit_titre_pdf), 56f, 80f, titre)
        c.drawText("${DateFormat.getDateInstance(DateFormat.LONG).format(Date())}  ·  $idCourt", 56f, 102f, gris)
        c.drawBitmap(qr, null, Rect(56, 130, 256, 330), null)
        mots.forEachIndexed { i, m ->
            val col = i / 6; val ligne = i % 6
            val x = 290f + col * 140f; val y = 158f + ligne * 30f
            c.drawText("${i + 1}".padStart(2), x, y, num)
            c.drawText(m, x + 24f, y, mot)
        }
        val aide = context.getString(R.string.cle_kit_aide_pdf, hote)
        var y = 380f
        val largeur = 483f
        var ligne = ""
        for (w in aide.split(" ")) {
            val essai = if (ligne.isEmpty()) w else "$ligne $w"
            if (gris.measureText(essai) > largeur) { c.drawText(ligne, 56f, y, gris); y += 16f; ligne = w } else ligne = essai
        }
        if (ligne.isNotEmpty()) c.drawText(ligne, 56f, y, gris)
        doc.finishPage(page)
        context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        true
    } catch (e: Exception) {
        false
    }

    /** Three distinct word positions (1-based), in order. */
    fun tirage(): List<Int> = (1..12).shuffled().take(3).sorted()

    fun correspond(attendu: String, saisi: String): Boolean = attendu.equals(saisi.trim(), ignoreCase = true)
}

/** Asks for 3 of the 12 words. Calls [onSucces] only if all three match. */
@Composable
internal fun QuizCle(mots: List<String>, onSucces: () -> Unit, onFermer: () -> Unit) {
    val positions = remember { Cle.tirage() }
    val saisies = remember { positions.map { mutableStateOf("") } }
    var faux by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.cle_quiz_titre)) },
        text = {
            Column {
                Text(stringResource(R.string.cle_quiz_texte, positions[0], positions[1], positions[2]))
                Spacer(Modifier.height(12.dp))
                positions.forEachIndexed { i, p ->
                    var v by saisies[i]
                    OutlinedTextField(
                        value = v, onValueChange = { v = it; faux = false }, singleLine = true,
                        label = { Text(stringResource(R.string.cle_quiz_mot, p)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (faux) Text(stringResource(R.string.cle_quiz_faux), color = Tokens.colors.danger)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = positions.withIndex().all { (i, p) -> Cle.correspond(mots[p - 1], saisies[i].value) }
                if (ok) onSucces() else faux = true
            }) { Text(stringResource(R.string.cle_quiz_valider)) }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.annuler)) } },
    )
}
