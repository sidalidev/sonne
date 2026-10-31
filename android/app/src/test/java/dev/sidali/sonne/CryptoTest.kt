package dev.sidali.sonne

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Reproduit docs/VECTEURS.json, partagés avec relay/crypto.js et la page web.
 * Le répertoire de travail des tests est android/app.
 */
class CryptoTest {

    private val vecteurs: String = File("../../docs/VECTEURS.json").readText()
    private val liste: List<String> =
        File("src/main/res/raw/bip39_english.txt").readLines().filter { it.isNotBlank() }

    /** Lecture minimale d'un champ texte du JSON (pas de bibliothèque JSON sur la JVM de test). */
    private fun champ(nom: String, apres: String? = null): String {
        val debut = if (apres == null) 0 else vecteurs.indexOf("\"$apres\"").also { check(it >= 0) }
        val m = Regex("\"" + Regex.escape(nom) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(vecteurs, debut) ?: error("Missing field: $nom")
        return m.groupValues[1].replace("\\\"", "\"")
    }

    private fun nombre(nom: String, apres: String): Long {
        val debut = vecteurs.indexOf("\"$apres\"")
        val m = Regex("\"" + nom + "\"\\s*:\\s*(\\d+)").find(vecteurs, debut) ?: error("Missing field: $nom")
        return m.groupValues[1].toLong()
    }

    private val s get() = Crypto.depuisHex(champ("S"))

    @Test
    fun listeBip39Officielle() {
        assertEquals(2048, liste.size)
        assertEquals("abandon", liste.first())
        assertEquals("zoo", liste.last())
        // Empreinte de la liste officielle (english.txt du dépôt bitcoin/bips).
        val empreinte = Crypto.hex(Crypto.sha256(File("src/main/res/raw/bip39_english.txt").readBytes()))
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", empreinte)
    }

    @Test
    fun preuveEtIdentifiant() {
        // Protocole v2 : P = HKDF(S, "sonne-phone", 16) et id = SHA-256("sonne-id" ‖ P)[0:32].
        assertEquals(champ("P"), Crypto.hex(Crypto.preuve(s)))
        assertEquals(champ("id"), Crypto.deriveId(s))
        assertEquals(champ("id"), Crypto.idDepuisPreuve(Crypto.depuisHex(champ("P"))))
    }

    @Test
    fun sousProtocolesEtAdresse() {
        val p = Crypto.depuisHex(champ("P"))
        assertEquals("sonne.v2, sonne.p.${champ("P")}", Reglages.sousProtocoles(p))
        // Rien d'identifiant dans l'URL.
        assertEquals("wss://sonne.sidali.dev/ws/phone", Reglages.urlWebSocket("https://sonne.sidali.dev/"))
        assertEquals("ws://10.0.2.2:7700/ws/phone", Reglages.urlWebSocket("http://10.0.2.2:7700"))
    }

    @Test
    fun relaisHttpSeulementEnDebug() {
        assertTrue(Reglages.relaisValide("https://sonne.sidali.dev", autoriserHttp = false))
        assertFalse(Reglages.relaisValide("http://ma-box:7700", autoriserHttp = false))
        assertTrue(Reglages.relaisValide("http://ma-box:7700", autoriserHttp = true))
        assertFalse(Reglages.relaisValide("https://", autoriserHttp = true))
        assertFalse(Reglages.relaisValide("https://a.b/#s=x", autoriserHttp = true))
        assertEquals("ma-box:7700", Reglages.hote("http://ma-box:7700/"))
    }

    @Test
    fun cles() {
        assertEquals(champ("K_sig"), Crypto.hex(Crypto.cleSignature(s)))
        assertEquals(champ("K_enc"), Crypto.hex(Crypto.cleChiffrement(s)))
    }

    @Test
    fun hkdfRfc5869CasDeTest3() {
        // RFC 5869, cas A.3 : sel et info vides.
        val ikm = ByteArray(22) { 0x0b }
        val okm = Crypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Crypto.hex(okm),
        )
    }

    @Test
    fun douzeMots() {
        val attendus = Regex("\"mots\"\\s*:\\s*\\[([^\\]]*)]").find(vecteurs)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }
        assertEquals(12, attendus.size)
        assertEquals(attendus, Crypto.versMots(s, liste))
        assertArrayEquals(s, Crypto.depuisMots(attendus.joinToString("  ").uppercase(), liste))
    }

    @Test
    fun motsAllerRetourEtSommeDeControle() {
        repeat(200) {
            val alea = Crypto.genererSecret()
            assertArrayEquals(alea, Crypto.depuisMots(Crypto.versMots(alea, liste).joinToString(" "), liste))
        }
        // Secret avec octets de tête nuls ou à 0xff : vérifie le recalage du BigInteger.
        for (octet in listOf(0x00, 0xff)) {
            val extreme = ByteArray(16) { octet.toByte() }
            assertArrayEquals(extreme, Crypto.depuisMots(Crypto.versMots(extreme, liste).joinToString(" "), liste))
        }
        val mots = Crypto.versMots(s, liste).toMutableList()
        mots[11] = if (mots[11] == "zoo") "abandon" else "zoo"
        try {
            Crypto.depuisMots(mots.joinToString(" "), liste)
            fail("The checksum should have been refused")
        } catch (e: IllegalArgumentException) {
            // attendu
        }
    }

    @Test
    fun lien() {
        assertEquals(champ("lien"), Crypto.versLien(s, "https://sonne.sidali.dev"))
        assertEquals(champ("lien"), Crypto.versLien(s, "https://sonne.sidali.dev/"))
    }

    @Test
    fun signatureDeCommande() {
        val kSig = Crypto.cleSignature(s)
        val ts = nombre("ts", "message")
        val nonce = champ("nonce", "commande")
        val sig = champ("sig")
        assertEquals(champ("texte_signe"), Crypto.texteASigner("ring", ts, nonce))
        assertEquals(sig, Crypto.signer(kSig, "ring", ts, nonce))
        assertTrue(Crypto.verifierSignature(kSig, "ring", ts, nonce, sig))
        assertFalse(Crypto.verifierSignature(kSig, "stop", ts, nonce, sig))
        assertFalse(Crypto.verifierSignature(kSig, "ring", ts + 1, nonce, sig))
        assertFalse(Crypto.verifierSignature(Crypto.cleChiffrement(s), "ring", ts, nonce, sig))
        assertFalse(Crypto.verifierSignature(kSig, "ring", ts, nonce, "zz"))
    }

    /** Clés de la chaîne des vecteurs, telles que le téléphone les tient. */
    private val cles: FiltreCommandes.Cles
        get() {
            val kSig = Crypto.cleSignature(s)
            return FiltreCommandes.Cles(Crypto.deriveId(s), kSig, Crypto.cleCommande(kSig))
        }

    /** Commande v2 signée avec un nonce aléatoire (ou donné), telle qu'elle arrive au téléphone. */
    private fun v2(cmd: String, ts: Long, nonce: String = Crypto.hex(Crypto.aleatoire(16))) =
        JSONObject(Crypto.messageCommandeV2(Crypto.cleSignature(s), cmd, ts, nonce))

    /** Commande v3 chiffrée avec un nonce aléatoire, telle qu'elle arrive au téléphone. */
    private fun v3(cmd: String, ts: Long, duree: Int = 0, lampe: Int = 0) =
        JSONObject(Crypto.chiffrerCommande(cles.kCmd, cles.id, cmd, ts, duree = duree, lampe = lampe))

    private fun FiltreCommandes.ok(message: JSONObject, maintenant: Long) =
        accepter(cles, message, maintenant) is FiltreCommandes.Issue.Acceptee

    private fun FiltreCommandes.raison(message: JSONObject, maintenant: Long): String =
        when (val issue = accepter(cles, message, maintenant)) {
            is FiltreCommandes.Issue.Acceptee -> "acceptee"
            is FiltreCommandes.Issue.Refusee -> issue.raison
        }

    @Test
    fun filtreFraicheurEtRejeu() {
        val ts = nombre("ts", "message")
        val commande = v2("ring", ts, champ("nonce", "commande"))
        assertEquals(champ("sig"), commande.getString("sig"))
        for (format in listOf(commande, v3("ring", ts))) {
            val filtre = FiltreCommandes()
            // Trop vieux ou trop en avance : refusé.
            assertEquals("fraicheur", filtre.raison(format, ts + Crypto.FENETRE_MS + 1))
            assertEquals("fraicheur", filtre.raison(format, ts - Crypto.FENETRE_MS - 1))
            // Dans la fenêtre : accepté une fois, puis rejeu refusé.
            assertTrue(filtre.ok(format, ts + Crypto.FENETRE_MS))
            assertEquals("rejeu", filtre.raison(format, ts))
            // Les nonces survivent à une sauvegarde puis un rechargement.
            assertEquals("rejeu", FiltreCommandes(filtre.nonces()).raison(format, ts))
        }
    }

    @Test
    fun filtreAntiRejeuParAgeEtNonParNombre() {
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes()
        val premier = v3("ping", ts)
        assertTrue(filtre.ok(premier, ts))
        // Bien plus de 200 commandes acceptées ensuite : le premier nonce n'est pas oublié
        // tant que sa commande est dans la fenêtre, le rejeu reste refusé.
        repeat(500) { i -> assertTrue(filtre.ok(if (i % 2 == 0) v2("ping", ts + 1) else v3("ping", ts + 1), ts + 1)) }
        assertTrue(premier.getString("nonce") in filtre.nonces())
        assertEquals("rejeu", filtre.raison(premier, ts + Crypto.FENETRE_MS))
        // Une fois hors de la fenêtre, il est oublié (et sa commande serait refusée de toute façon).
        assertTrue(filtre.ok(v3("ping", ts + Crypto.FENETRE_MS + 2), ts + Crypto.FENETRE_MS + 2))
        assertFalse(premier.getString("nonce") in filtre.nonces())
    }

    @Test
    fun filtrePleinRefuseAuLieuDOublier() {
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes(capacite = 3)
        repeat(3) { assertTrue(filtre.ok(if (it == 0) v2("ping", ts) else v3("ping", ts), ts)) }
        assertEquals("capacite", filtre.raison(v3("ping", ts), ts))
        assertEquals("capacite", filtre.raison(v2("ping", ts), ts))
        assertEquals(3, filtre.nonces().size)
    }

    @Test
    fun filtreOrdreDesRingEtStop() {
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes()
        val ring = v3("ring", ts)
        // La stop arrive d'abord : une ring plus ancienne livrée ensuite ne fait pas sonner.
        assertTrue(filtre.ok(v2("stop", ts + 1000), ts + 2000))
        assertEquals("ordre", filtre.raison(ring, ts + 2000))
        // Son nonce est quand même retenu.
        assertTrue(ring.getString("nonce") in filtre.nonces())
        // Le ping n'est pas concerné par l'ordre.
        assertTrue(filtre.ok(v3("ping", ts), ts + 2000))
        // Une ring plus récente passe, et l'ordre survit à une sauvegarde, pour les deux formats.
        val ring2 = v3("ring", ts + 1500)
        assertTrue(filtre.ok(ring2, ts + 2000))
        val recharge = FiltreCommandes(filtre.nonces(), filtre.dernierTsOrdre)
        assertEquals("ordre", recharge.raison(v2("stop", ts + 1200), ts + 2000))
        assertEquals("ordre", recharge.raison(v3("stop", ts + 1200), ts + 2000))
        assertEquals("rejeu", recharge.raison(ring2, ts + 2000))
    }

    @Test
    fun filtreMemeMemoirePourV2EtV3() {
        // Une commande reçue par le relais puis par Bluetooth (ou l'inverse) n'est exécutée qu'une fois.
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes()
        val ring = v3("ring", ts)
        assertNull(filtre.recevable(cles, ring, ts))
        assertTrue(filtre.ok(ring, ts))
        assertEquals("rejeu", filtre.recevable(cles, ring, ts))
        // recevable ne modifie rien : une commande vue seulement par lui reste acceptable.
        val stop = v3("stop", ts + 1)
        val avant = filtre.revision
        assertNull(filtre.recevable(cles, stop, ts))
        assertEquals(avant, filtre.revision)
        assertTrue(filtre.ok(stop, ts))
        // Un refus avant le rejeu ne touche pas à la mémoire (aucune écriture sur disque).
        val r = filtre.revision
        assertEquals("fraicheur", filtre.raison(v3("ping", ts - Crypto.FENETRE_MS - 1), ts))
        assertEquals(r, filtre.revision)
    }

    @Test
    fun filtreFormes() {
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes()
        val bon = v3("ring", ts)
        fun variante(bloc: JSONObject.() -> Unit) = JSONObject(bon.toString()).apply(bloc)
        assertEquals("forme", filtre.raison(variante { put("type", "state") }, ts))
        assertEquals("forme", filtre.raison(variante { remove("type") }, ts))
        assertEquals("forme", filtre.raison(variante { put("v", "3") }, ts))
        assertEquals("forme", filtre.raison(variante { put("v", 4) }, ts))
        assertEquals("forme", filtre.raison(variante { put("v", JSONObject.NULL) }, ts))
        assertEquals("forme", filtre.raison(variante { remove("nonce") }, ts))
        assertEquals("forme", filtre.raison(variante { put("nonce", "a0a1a2a3a4a5a6a7a8a9aa") }, ts))
        assertEquals("forme", filtre.raison(variante { put("enc", 12) }, ts))
        assertEquals("forme", filtre.raison(variante { put("enc", "-" + getString("enc").drop(1)) }, ts))
        assertEquals("taille", filtre.raison(variante { put("enc", getString("enc").dropLast(4)) }, ts))
        // Un nonce changé fait échouer l'étiquette (il est authentifié).
        val autre = Crypto.hex(Crypto.aleatoire(12))
        assertEquals("authentification", filtre.raison(variante { put("nonce", autre) }, ts))
        // Champs en plus ignorés, comme ceux que le relais retire.
        assertTrue(filtre.ok(variante { put("cmd", "stop") }, ts))
        // Une v2 mal signée ou sans ts lisible.
        assertEquals("signature", filtre.raison(JSONObject(v2("ring", ts).toString()).put("cmd", "stop"), ts))
        assertEquals("signature", filtre.raison(JSONObject(v2("ring", ts).toString()).put("ts", ts + 0.5), ts))
        assertEquals("signature", filtre.raison(JSONObject(v2("ring", ts).toString()).put("ts", "$ts"), ts))
    }

    @Test
    fun transitionEnCours() {
        // App 1.6 : la v2 reste acceptée, un téléphone ne refuse pas ce qu'une page encore en
        // transition peut envoyer. La coupure n'arrive qu'avec l'app 1.8 (docs/PROTOCOL.md).
        if (BuildConfig.VERSION_NAME.startsWith("1.6.")) assertTrue(Crypto.ACCEPTER_V2)
    }

    @Test
    fun cleDeCommande() {
        // RFC 5869, cas A.1, étape Expand seule (PRK donnée par la RFC).
        val prk = Crypto.depuisHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hex(Crypto.hkdfExpand(prk, Crypto.depuisHex("f0f1f2f3f4f5f6f7f8f9"), 42)),
        )
        val kSig = Crypto.depuisHex(champ("K_sig"))
        val kCmd = Crypto.cleCommande(kSig)
        assertEquals(champ("K_cmd", "commandes_v3"), Crypto.hex(kCmd))
        // Un seul bloc : HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01).
        assertArrayEquals(kCmd, Crypto.hmacSha256(kSig, "sonne-cmd-v3".toByteArray() + byteArrayOf(1)))
        assertEquals(champ("aad_v3", "commandes_v3"), String(Crypto.aadCommande(Crypto.deriveId(s)), Charsets.UTF_8))
    }

    @Test
    fun commandesChiffreesOctetPourOctet() {
        val kCmd = Crypto.depuisHex(champ("K_cmd", "commandes_v3"))
        val id = champ("id")
        for (nom in listOf("v3_ring", "v3_stop", "v3_ping", "v3_ring_parametres")) {
            val cmd = champ("cmd", nom)
            val ts = nombre("ts", nom)
            val duree = nombre("duree", nom).toInt()
            val lampe = nombre("lampe", nom).toInt()
            val nonce = Crypto.depuisHex(champ("nonce", nom))
            assertEquals(nom, champ("clair", nom), Crypto.hex(Crypto.clairCommande(cmd, ts, duree, lampe)))
            val message = Crypto.chiffrerCommande(kCmd, id, cmd, ts, nonce, duree, lampe)
            assertEquals(nom, champ("texte", nom), message)
            assertEquals(nom, champ("enc", nom), JSONObject(message).getString("enc"))
            assertEquals(Crypto.TAILLE_MESSAGE_COMMANDE, message.toByteArray(Charsets.UTF_8).size)
            assertEquals(nombre("octets", nom).toInt(), message.toByteArray(Charsets.UTF_8).size)
            // Et le déchiffrement redonne la commande.
            val lu = Crypto.dechiffrerCommande(kCmd, id, champ("nonce", nom), champ("enc", nom))
            assertEquals(Crypto.CommandeLue(3, cmd, ts, champ("nonce", nom), duree, lampe), lu)
        }
        // La commande v2 des vecteurs, écrite à la main, est le texte du cas de transition.
        assertEquals(
            champ("texte", "accepte_ancien_format_transition"),
            Crypto.messageCommandeV2(Crypto.cleSignature(s), "ring", nombre("ts", "message"), champ("nonce", "commande")),
        )
    }

    @Test
    fun commandesChiffreesTailleFixe() {
        // Toujours 172 octets et un seul « = », quels que soient la commande, le nonce et les paramètres.
        val ts = 1_792_300_000_000L
        val reglages = listOf("ring" to (0 to 0), "ring" to (600 to 1), "ring" to (60 to 2), "stop" to (0 to 0), "ping" to (0 to 0))
        repeat(200) { i ->
            val (cmd, p) = reglages[i % reglages.size]
            val message = Crypto.chiffrerCommande(cles.kCmd, cles.id, cmd, ts + i, duree = p.first, lampe = p.second)
            assertEquals(Crypto.TAILLE_MESSAGE_COMMANDE, message.toByteArray(Charsets.UTF_8).size)
            assertTrue(message.endsWith("=\"}") && !message.endsWith("==\"}"))
            assertFalse(message.contains("\\"))
        }
        // Règles d'envoi : durée 0 ou 60 à 600 s, lampe 0 à 2, rien pour stop et ping.
        for ((cmd, duree, lampe) in listOf(Triple("ring", 59, 0), Triple("ring", 601, 0), Triple("ring", 0, 3), Triple("stop", 60, 0), Triple("ping", 0, 1))) {
            try {
                Crypto.clairCommande(cmd, ts, duree, lampe)
                fail("Parameters wrongly accepted: $cmd $duree $lampe")
            } catch (e: IllegalArgumentException) {
                // attendu
            }
        }
    }

    @Test
    fun parametresDeSonnerie() {
        assertEquals(Crypto.ParametresSonnerie(null, null), Crypto.parametresSonnerie(0, 0))
        assertEquals(Crypto.ParametresSonnerie(300, false), Crypto.parametresSonnerie(300, 2))
        assertEquals(Crypto.ParametresSonnerie(60, true), Crypto.parametresSonnerie(60, 1))
        assertEquals(Crypto.ParametresSonnerie(600, null), Crypto.parametresSonnerie(600, 0))
        // Valeur inattendue : le réglage du téléphone, jamais un refus.
        assertEquals(Crypto.ParametresSonnerie(null, null), Crypto.parametresSonnerie(59, 3))
        assertEquals(Crypto.ParametresSonnerie(null, null), Crypto.parametresSonnerie(601, 255))
        assertEquals(Crypto.ParametresSonnerie(null, null), Crypto.parametresSonnerie(65535, 0))
    }

    @Test
    fun etatAvecCapacite() {
        val clair = champ("clair", "etat_v3")
        assertEquals(nombre("octets_clair", "etat_v3").toInt(), clair.toByteArray(Charsets.UTF_8).size)
        // Le téléphone écrit exactement ce clair (cmdv après model, avant pad).
        val ecrit = Proximite.bourrerEtat(
            Proximite.clairEtat(1_792_300_000_050, true, 77, false, "1.6.0", "a0a1a2a3a4a5a6a7a8a9aaab", "Pixel 10 Pro"),
        )
        assertEquals(clair, ecrit)
        val kEnc = Crypto.cleChiffrement(s)
        val id = Crypto.deriveId(s)
        val nonce = champ("nonce", "etat_v3")
        val chiffre = Crypto.chiffrerEtat(kEnc, id, ecrit, Crypto.depuisHex(nonce))
        assertEquals(champ("enc", "etat_v3"), chiffre.enc)
        val message = Proximite.messageEtat(chiffre.nonce, chiffre.enc)
        assertEquals(champ("texte", "etat_v3"), message)
        assertEquals(nombre("octets", "etat_v3").toInt(), message.toByteArray(Charsets.UTF_8).size)
        // Lecture par un chercheur : cmdv ≥ 3 annonce les commandes chiffrées.
        val lu = JSONObject(Crypto.dechiffrerEtat(kEnc, id, nonce, champ("enc", "etat_v3")))
        assertTrue(Crypto.accepteV3(lu.opt(Crypto.CHAMP_CAPACITE)))
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaab", lu.getString("ack"))
        // Un état 1.5 (sans cmdv) ou une valeur qui n'est pas un entier ≥ 3 : v2 seulement.
        assertFalse(Crypto.accepteV3(JSONObject(champ("plaintext")).opt(Crypto.CHAMP_CAPACITE)))
        for (valeur in listOf<Any?>("3", 2, 2.5, true, JSONObject.NULL)) assertFalse("$valeur", Crypto.accepteV3(valeur))
        for (valeur in listOf<Any?>(3, 4L, 3.0)) assertTrue("$valeur", Crypto.accepteV3(valeur))
    }

    /** Valeur facultative d'un cas : cherchée seulement dans son propre bloc (avant son champ "note"). */
    private fun champDuCas(nom: String, cas: String): String? {
        val debut = vecteurs.indexOf("\"$cas\"")
        val fin = vecteurs.indexOf("\"note\"", debut)
        val m = Regex("\"" + nom + "\"\\s*:\\s*\"([^\"]*)\"").find(vecteurs.substring(debut, fin)) ?: return null
        return m.groupValues[1]
    }

    @Test
    fun casDAcceptationEtDeRefus() {
        val noms = JSONObject(vecteurs).getJSONObject("commandes_v3").getJSONObject("cas").keys().asSequence().toList()
        assertEquals(19, noms.size)
        for (nom in noms) {
            val texte = champ("texte", apres = nom)
            val maintenant = nombre("maintenant", nom)
            val phase = champ("phase", apres = nom)
            val attendu = champ("attendu", apres = nom)
            assertTrue(nom, phase == "transition" || phase == "coupure")
            val filtre = FiltreCommandes(accepterV2 = phase == "transition")
            champDuCas("avant", nom)?.let { avant ->
                assertEquals("$nom (avant)", "acceptee", filtre.raison(JSONObject(champ("texte", avant)), maintenant))
            }
            // Écriture GATT : mêmes refus jusqu'au rejeu, sans rien modifier (l'ordre vient après).
            val recevable = filtre.recevable(cles, JSONObject(texte), maintenant)
            assertEquals("$nom (recevable)", if (attendu == "acceptee" || attendu == "ordre") null else attendu, recevable)
            val issue = filtre.accepter(cles, JSONObject(texte), maintenant)
            val obtenu = if (issue is FiltreCommandes.Issue.Refusee) issue.raison else "acceptee"
            assertEquals(nom, attendu, obtenu)
        }
        // Paramètres du cas accepte_parametres : 300 s, lampe éteinte.
        val issue = FiltreCommandes().accepter(cles, JSONObject(champ("texte", "accepte_parametres")), 1_792_300_005_000)
        val c = (issue as FiltreCommandes.Issue.Acceptee).commande
        assertEquals(Crypto.ParametresSonnerie(300, false), Crypto.parametresSonnerie(c.duree, c.lampe))
        assertEquals("d0d1d2d3d4d5d6d7d8d9dadb", c.nonce)
    }

    @Test
    fun baliseBluetooth() {
        // Bloc "ble" de VECTEURS.json, identique au test Node (relay/test/crypto.test.js).
        val w = nombre("w", "ble")
        assertEquals(champ("B", "ble"), Crypto.hex(Crypto.cleBle(s)))
        assertEquals(champ("w_octets", "ble"), Crypto.hex(java.nio.ByteBuffer.allocate(8).putLong(w).array()))
        assertEquals(champ("id_ble", "ble"), Crypto.hex(Crypto.deriveBleId(s, w)))
        assertEquals(champ("donnees_service", "ble"), Crypto.hex(Proximite.donneesService(Crypto.deriveBleId(s, w))))
        assertEquals(champ("id_ble_precedent", "ble"), Crypto.hex(Crypto.deriveBleId(s, w - 1)))
        assertEquals(champ("id_ble_suivant", "ble"), Crypto.hex(Crypto.deriveBleId(s, w + 1)))
        // w est la fenêtre de l'horodatage de la commande des vecteurs.
        assertEquals(w, Crypto.fenetreBle(nombre("ts", "message")))
        assertEquals(w, Crypto.fenetreBle(w * Crypto.DUREE_FENETRE_BLE_MS))
        assertEquals(w - 1, Crypto.fenetreBle(w * Crypto.DUREE_FENETRE_BLE_MS - 1))
    }

    @Test
    fun lienVersSecret() {
        assertArrayEquals(s, Crypto.depuisLien(champ("lien")))
        assertArrayEquals(s, Crypto.depuisLien("  http://10.0.2.2:7700/#s=AAECAwQFBgcICQoLDA0ODw== "))
        val mauvais = listOf(
            "https://sonne.sidali.dev/",
            "https://x/#s=",
            "https://x/#s=AAEC",
            "https://x/#t=AAECAwQFBgcICQoLDA0ODw",
            "https://x/#s=AAEC*wQFBgcICQoLDA0ODw",
        )
        for (lien in mauvais) {
            try {
                Crypto.depuisLien(lien)
                fail("Link wrongly accepted: $lien")
            } catch (e: IllegalArgumentException) {
                // attendu
            }
        }
    }

    @Test
    fun dechiffrementDEtat() {
        val kEnc = Crypto.cleChiffrement(s)
        val id = champ("aad")
        val nonce = champ("nonce", "etat")
        val enc = champ("enc")
        assertEquals(champ("plaintext"), Crypto.dechiffrerEtat(kEnc, id, nonce, enc))
        // Le chiffrement avec le même nonce redonne exactement le vecteur.
        assertEquals(enc, Crypto.chiffrerEtat(kEnc, id, champ("plaintext"), Crypto.depuisHex(nonce)).enc)
        // Une autre AAD doit être refusée.
        try {
            Crypto.dechiffrerEtat(kEnc, "0".repeat(32), nonce, enc)
            fail("Wrong AAD accepted")
        } catch (e: Exception) {
            // attendu
        }
    }
}
