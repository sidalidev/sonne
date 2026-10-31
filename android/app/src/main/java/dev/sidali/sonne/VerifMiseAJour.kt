package dev.sidali.sonne

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Annonce de mise à jour publiée par le relais (GET /update/android.json), déjà validée.
 * `certSha256` : empreinte SHA-256 du certificat de signature (octets DER), en hex minuscule.
 */
data class Annonce(
    val version: String,
    val versionCode: Long,
    val url: String,
    val sha256: String,
    val certSha256: String,
    val notes: String,
)

/** Pourquoi une mise à jour est refusée ou a échoué. */
enum class RaisonEchec { RESEAU, TAILLE, SHA256, CERTIFICAT, PAQUET, STOCKAGE, INSTALLATION }

/**
 * Vérifications de la mise à jour intégrée, en Kotlin pur (testées sur la JVM) :
 * comparaison de versions, SHA-256 du fichier, certificat de signature.
 * Voir docs/PROTOCOL.md, section « Update (app 1.2) ».
 */
object VerifMiseAJour {

    /** Taille maximale d'un APK téléchargé (l'APK de Sonne fait quelques Mo). */
    const val TAILLE_MAX = 150L * 1024 * 1024

    private val RE_HEX64 = Regex("^[0-9a-f]{64}$")
    private val RE_VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")

    /**
     * Compare deux noms de version « 1.2.0 », « 1.10 », « 1.2.0-rc1 » : parties numériques
     * une à une (une partie absente vaut 0), un suffixe après « - » ou « + » est ignoré.
     * Négatif si a < b, 0 si égales, positif si a > b.
     */
    fun comparerVersions(a: String, b: String): Int {
        fun parties(v: String) = v.trim().removePrefix("v").split('-', '+')[0].split('.')
            .map { p -> p.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
        val pa = parties(a)
        val pb = parties(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    /** Le versionCode fait foi : une mise à jour est proposée seulement s'il est plus grand. */
    fun plusRecente(annonce: Annonce, versionCodeInstalle: Long): Boolean = annonce.versionCode > versionCodeInstalle

    /**
     * Adresse de téléchargement acceptable : https:// (http:// seulement en debug, relais local
     * de test), sans espace ni fragment.
     */
    fun urlAcceptable(url: String, autoriserHttp: Boolean): Boolean {
        val reste = when {
            url.startsWith("https://") -> url.removePrefix("https://")
            url.startsWith("http://") && autoriserHttp -> url.removePrefix("http://")
            else -> return false
        }
        return reste.isNotEmpty() && !reste.startsWith("/") && reste.none { it.isWhitespace() } && '#' !in reste
    }

    /** Construit une annonce validée, ou null si un champ manque ou n'a pas la bonne forme. */
    fun annonce(
        version: String?,
        versionCode: Long?,
        url: String?,
        sha256: String?,
        certSha256: String?,
        notes: String?,
        autoriserHttp: Boolean,
    ): Annonce? {
        if (version == null || !RE_VERSION.matches(version)) return null
        if (versionCode == null || versionCode <= 0) return null
        if (url == null || url.length > 512 || !urlAcceptable(url, autoriserHttp)) return null
        val sha = sha256?.lowercase() ?: return null
        val cert = certSha256?.lowercase()?.replace(":", "") ?: return null
        if (!RE_HEX64.matches(sha) || !RE_HEX64.matches(cert)) return null
        return Annonce(version, versionCode, url, sha, cert, notes.orEmpty().take(2000))
    }

    fun hex(octets: ByteArray): String = octets.joinToString("") { "%02x".format(it) }

    fun sha256Hex(octets: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(octets))

    fun sha256Hex(entree: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val tampon = ByteArray(64 * 1024)
        while (true) {
            val n = entree.read(tampon)
            if (n < 0) break
            md.update(tampon, 0, n)
        }
        return hex(md.digest())
    }

    /** Égalité de deux empreintes hex, sans fuite par le temps de comparaison. */
    fun memeEmpreinte(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    /** true si le SHA-256 du fichier est celui annoncé. */
    fun verifierSha256(fichier: File, attendu: String): Boolean =
        fichier.isFile && memeEmpreinte(fichier.inputStream().use { sha256Hex(it) }, attendu)

    /**
     * Certificats de signature (octets DER) : ceux de l'APK téléchargé doivent être exactement
     * ceux de l'app installée, et contenir celui de l'annonce. Renvoie null si tout concorde,
     * sinon la raison du refus.
     */
    fun verifierCertificats(apk: List<ByteArray>, installe: List<ByteArray>, certAnnonce: String): RaisonEchec? {
        if (apk.isEmpty() || installe.isEmpty()) return RaisonEchec.CERTIFICAT
        val empreintesApk = apk.map { sha256Hex(it) }.toSet()
        val empreintesInstalle = installe.map { sha256Hex(it) }.toSet()
        if (empreintesApk != empreintesInstalle) return RaisonEchec.CERTIFICAT
        if (empreintesApk.none { memeEmpreinte(it, certAnnonce) }) return RaisonEchec.CERTIFICAT
        return null
    }
}
