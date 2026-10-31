package dev.sidali.sonne

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mise à jour intégrée : versions, SHA-256 du fichier, certificat de signature (faux octets). */
class MiseAJourTest {

    private fun annonce(code: Long, cert: String = "0".repeat(64)) =
        Annonce("1.2.1", code, "https://sonne.sidali.dev/download/sonne-1.2.1.apk", "a".repeat(64), cert, "")

    @Test
    fun comparaisonDesVersions() {
        assertEquals(0, VerifMiseAJour.comparerVersions("1.2.0", "1.2.0"))
        assertEquals(0, VerifMiseAJour.comparerVersions("1.2", "1.2.0"))
        assertTrue(VerifMiseAJour.comparerVersions("1.2.1", "1.2.0") > 0)
        assertTrue(VerifMiseAJour.comparerVersions("1.10.0", "1.9.9") > 0)
        assertTrue(VerifMiseAJour.comparerVersions("1.2.0", "1.10.0") < 0)
        assertTrue(VerifMiseAJour.comparerVersions("2.0", "1.99.99") > 0)
        assertEquals(0, VerifMiseAJour.comparerVersions("v1.2.0", "1.2.0-rc1"))
        assertTrue(VerifMiseAJour.comparerVersions("1.1.0", "1.2.0") < 0)
    }

    @Test
    fun versionCodeFaitFoi() {
        assertTrue(VerifMiseAJour.plusRecente(annonce(5), 4))
        assertFalse(VerifMiseAJour.plusRecente(annonce(4), 4))
        assertFalse(VerifMiseAJour.plusRecente(annonce(3), 4))
    }

    @Test
    fun validationDeLAnnonce() {
        val sha = "AB".repeat(32)
        val cert = "cd:".repeat(31) + "cd"
        val ok = VerifMiseAJour.annonce("1.2.0", 4, "https://sonne.sidali.dev/download/sonne-1.2.0.apk", sha, cert, "notes", false)
        assertNotNull(ok)
        assertEquals("ab".repeat(32), ok!!.sha256)
        assertEquals("cd".repeat(32), ok.certSha256)
        // http:// seulement en debug.
        assertNull(VerifMiseAJour.annonce("1.2.0", 4, "http://10.0.2.2:7700/download/a.apk", sha, cert, "", false))
        assertNotNull(VerifMiseAJour.annonce("1.2.0", 4, "http://10.0.2.2:7700/download/a.apk", sha, cert, "", true))
        assertNull(VerifMiseAJour.annonce("1.2.0", 0, "https://x/a.apk", sha, cert, "", false))
        assertNull(VerifMiseAJour.annonce("1.2.0", 4, "https://x/a.apk", "abc", cert, "", false))
        assertNull(VerifMiseAJour.annonce("1.2.0", 4, "https://x/a.apk", sha, null, "", false))
        assertNull(VerifMiseAJour.annonce("1.2 0", 4, "https://x/a.apk", sha, cert, "", false))
        assertNull(VerifMiseAJour.annonce("1.2.0", 4, "ftp://x/a.apk", sha, cert, "", false))
        assertNull(VerifMiseAJour.annonce("1.2.0", 4, "https:///a.apk", sha, cert, "", false))
    }

    @Test
    fun sha256DuFichier() {
        val f = File.createTempFile("sonne", ".apk")
        try {
            f.writeText("abc")
            // SHA-256("abc"), vecteur FIPS 180-2.
            val attendu = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(VerifMiseAJour.verifierSha256(f, attendu))
            assertTrue(VerifMiseAJour.verifierSha256(f, attendu.uppercase()))
            assertFalse(VerifMiseAJour.verifierSha256(f, attendu.replaceRange(0, 1, "c")))
            f.appendText("d")
            assertFalse(VerifMiseAJour.verifierSha256(f, attendu))
            assertFalse(VerifMiseAJour.verifierSha256(File(f.path + ".absent"), attendu))
        } finally {
            f.delete()
        }
    }

    @Test
    fun certificatIdentiqueAccepte() {
        val cert = "faux certificat DER de Sonne".toByteArray()
        val empreinte = VerifMiseAJour.sha256Hex(cert)
        assertNull(VerifMiseAJour.verifierCertificats(listOf(cert), listOf(cert.copyOf()), empreinte))
        assertNull(VerifMiseAJour.verifierCertificats(listOf(cert), listOf(cert), empreinte.uppercase()))
    }

    @Test
    fun certificatDifferentRefuse() {
        val vrai = "faux certificat DER de Sonne".toByteArray()
        val autre = "certificat d'un attaquant".toByteArray()
        val empreinteVraie = VerifMiseAJour.sha256Hex(vrai)
        val empreinteAutre = VerifMiseAJour.sha256Hex(autre)
        // APK signé par une autre clé, même si l'annonce (compromise) donne son empreinte.
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(autre), listOf(vrai), empreinteAutre))
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(autre), listOf(vrai), empreinteVraie))
        // Bon certificat, mais l'annonce en donne un autre.
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(vrai), listOf(vrai), empreinteAutre))
        // Un octet de différence.
        val presque = vrai.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(presque), listOf(vrai), empreinteVraie))
        // Signataire supplémentaire, ou aucun.
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(vrai, autre), listOf(vrai), empreinteVraie))
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(emptyList(), listOf(vrai), empreinteVraie))
        assertEquals(RaisonEchec.CERTIFICAT, VerifMiseAJour.verifierCertificats(listOf(vrai), emptyList(), empreinteVraie))
    }
}
