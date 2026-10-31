package dev.sidali.sonne

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import kotlin.math.exp

/** Logique pure de la proximité Bluetooth (docs/PROTOCOLE.md, « Proximité Bluetooth »). */
class ProximiteTest {

    private val s = Crypto.depuisHex("000102030405060708090a0b0c0d0e0f")
    private val w = 1_991_444L

    @Test
    fun uuidsDuProtocole() {
        assertEquals("2af8c8b3-3a13-489b-a3a3-ac220005799b", Proximite.UUID_SERVICE.toString())
        assertEquals("2af8c8b4-3a13-489b-a3a3-ac220005799b", Proximite.UUID_COMMANDE.toString())
        assertEquals("2af8c8b5-3a13-489b-a3a3-ac220005799b", Proximite.UUID_ETAT.toString())
    }

    @Test
    fun donneesEtFiltre() {
        val id = Crypto.deriveBleId(s, w)
        val donnees = Proximite.donneesService(id)
        assertEquals(9, donnees.size)
        assertEquals(1, donnees[8].toInt())
        assertArrayEquals(id, Proximite.lireIdBle(donnees))
        // Paquet legacy : 3 (drapeaux) + 1 + 1 + 16 + 9 = 30 octets, sous la limite de 31.
        assertTrue(3 + 1 + 1 + 16 + donnees.size <= 31)
        // Le filtre ne compare que l'octet de version.
        assertEquals("000000000000000001", Crypto.hex(Proximite.donneesFiltre()))
        assertEquals("0000000000000000ff", Crypto.hex(Proximite.masqueFiltre()))
        // Version inconnue ou données trop courtes : ignorées.
        assertNull(Proximite.lireIdBle(id + 0x02))
        assertNull(Proximite.lireIdBle(id))
        assertNull(Proximite.lireIdBle(null))
    }

    @Test
    fun reconnaissanceSurTroisFenetres() {
        val autre = Crypto.depuisHex("ffeeddccbbaa99887766554433221100")
        val r = Reconnaissance(mapOf("moi" to Crypto.cleBle(s), "autre" to Crypto.cleBle(autre)))
        r.maj(w)
        for (d in -1L..1L) {
            assertEquals("moi", r.reconnaitre(Proximite.donneesService(Crypto.deriveBleId(s, w + d))))
            assertEquals("autre", r.reconnaitre(Proximite.donneesService(Crypto.deriveBleId(autre, w + d))))
        }
        // Deux fenêtres d'écart : plus reconnue.
        assertNull(r.reconnaitre(Proximite.donneesService(Crypto.deriveBleId(s, w + 2))))
        assertNull(r.reconnaitre(Proximite.donneesService(ByteArray(8))))
        // La table suit la fenêtre.
        r.maj(w + 2)
        assertEquals(w + 2, r.fenetreCalculee)
        assertEquals("moi", r.reconnaitre(Proximite.donneesService(Crypto.deriveBleId(s, w + 2))))
        assertNull(r.reconnaitre(Proximite.donneesService(Crypto.deriveBleId(s, w))))
    }

    @Test
    fun statutDesEcrituresGatt() {
        assertEquals(Proximite.GATT_SUCCESS, Proximite.statutEcriture(false, 0, 165))
        assertEquals(Proximite.GATT_SUCCESS, Proximite.statutEcriture(false, 0, 512))
        assertEquals(Proximite.GATT_INVALID_ATTRIBUTE_LENGTH, Proximite.statutEcriture(false, 0, 513))
        assertEquals(Proximite.GATT_INVALID_ATTRIBUTE_LENGTH, Proximite.statutEcriture(false, 0, 0))
        assertEquals(Proximite.GATT_REQUEST_NOT_SUPPORTED, Proximite.statutEcriture(true, 0, 10))
        assertEquals(Proximite.GATT_REQUEST_NOT_SUPPORTED, Proximite.statutEcriture(false, 4, 10))
        // Transition (1.6) : v2 (165 octets au plus) et v3 (172) passent.
        assertEquals(Proximite.GATT_SUCCESS, Proximite.statutEcriture(false, 0, 165, accepterV2 = true))
        assertEquals(Proximite.GATT_SUCCESS, Proximite.statutEcriture(false, 0, 172, accepterV2 = true))
        // Coupure (app 1.8) : exactement 172 octets, sinon longueur invalide (0x0d).
        assertEquals(Proximite.GATT_SUCCESS, Proximite.statutEcriture(false, 0, 172, accepterV2 = false))
        for (taille in listOf(1, 165, 171, 173, 512)) {
            assertEquals(Proximite.GATT_INVALID_ATTRIBUTE_LENGTH, Proximite.statutEcriture(false, 0, taille, accepterV2 = false))
        }
        assertEquals(Proximite.GATT_REQUEST_NOT_SUPPORTED, Proximite.statutEcriture(true, 0, 172, accepterV2 = false))
    }

    @Test
    fun clairDEtatEcritALaMain() {
        // Ordre du protocole, cmdv avant le bourrage, rien d'échappé en trop.
        assertEquals(
            """{"ts":1,"ringing":false,"battery":7,"charging":true,"app":"1.6.0","ack":null,"model":"A/B","cmdv":3}""",
            Proximite.clairEtat(1, false, 7, true, "1.6.0", null, "A/B"),
        )
        // Guillemets, barre oblique inverse et caractères de contrôle échappés : le JSON reste lisible.
        assertEquals("\"a\\\"b\\\\c\\u0001\"", Proximite.texteJson("a\"b\\c\u0001"))
        // Modèle tronqué à 64 caractères, et le clair bourré fait toujours 320 octets.
        val long = Proximite.clairEtat(1_792_300_000_000, true, 100, true, "1.6.0", "a".repeat(32), "é".repeat(200))
        assertTrue(long.contains("\"model\":\"${"é".repeat(Proximite.MODELE_MAX)}\","))
        assertEquals(Proximite.TAILLE_CLAIR_ETAT, Proximite.bourrerEtat(long).toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun lissageExponentielPondereParLeTemps() {
        val suivi = SuiviSignal()
        suivi.mesure(-90, 1_000)
        assertEquals(-90.0, suivi.lisse!!, 1e-9)
        suivi.mesure(-60, 3_000) // Δt = τ : α = 1 - 1/e
        val attendu = -90.0 + (1 - exp(-1.0)) * 30
        assertEquals(attendu, suivi.lisse!!, 1e-9)
        assertEquals(-60, suivi.derniereMesure)
        // 127 = RSSI indisponible : ignoré.
        suivi.mesure(127, 3_100)
        assertEquals(attendu, suivi.lisse!!, 1e-9)
        assertEquals(3_000, suivi.dernierVu)
    }

    @Test
    fun niveauxEtPerte() {
        assertEquals(1, Proximite.niveauBrut(-95.0))
        assertEquals(2, Proximite.niveauBrut(-88.0))
        assertEquals(3, Proximite.niveauBrut(-75.0))
        assertEquals(4, Proximite.niveauBrut(-64.5))
        assertEquals(5, Proximite.niveauBrut(-40.0))
        val suivi = SuiviSignal()
        assertEquals(0, suivi.niveau(0))
        suivi.mesure(-50, 0)
        assertEquals(5, suivi.niveau(0))
        // Plus vue depuis plus de 10 s : perdue, et le lissage repart de la mesure suivante.
        assertFalse(suivi.vueRecemment(10_001))
        assertEquals(0, suivi.niveau(10_001))
        assertNull(suivi.lisse)
        suivi.mesure(-95, 10_500)
        assertEquals(-95.0, suivi.lisse!!, 1e-9)
        assertEquals(1, suivi.niveau(10_500))
    }

    @Test
    fun hysteresis() {
        // Niveau 5 (seuil -64) : on y reste jusqu'à -67, on redescend sous -67.
        assertEquals(5, Proximite.niveauAvecHysteresis(-66.0, 5))
        assertEquals(5, Proximite.niveauAvecHysteresis(-67.0, 5))
        assertEquals(4, Proximite.niveauAvecHysteresis(-67.5, 5))
        // On monte dès le seuil atteint.
        assertEquals(5, Proximite.niveauAvecHysteresis(-64.0, 4))
        // Chute franche : plusieurs niveaux d'un coup.
        assertEquals(1, Proximite.niveauAvecHysteresis(-100.0, 5))
        // Juste sous le seuil du niveau 3 (-80) mais dans la marge : on reste à 3.
        assertEquals(3, Proximite.niveauAvecHysteresis(-82.0, 3))
        // Pas de clignotement : un signal qui oscille autour de -72 ne fait pas varier la jauge.
        var n = Proximite.niveauAvecHysteresis(-72.0, 3)
        assertEquals(4, n)
        for (r in listOf(-73.0, -71.5, -74.0, -72.5, -73.9)) {
            n = Proximite.niveauAvecHysteresis(r, n)
            assertEquals(4, n)
        }
    }

    @Test
    fun rythmeDesBips() {
        val r = Proximite.RYTHME_BIP_MS
        assertEquals(0L, r[0])
        for (i in 2..Proximite.NIVEAU_MAX) assertTrue("the beep speeds up", r[i] < r[i - 1])
    }

    @Test
    fun limiteDeDebit() {
        val limite = LimiteDebit(20, 60_000)
        repeat(20) { assertTrue(limite.autoriser(1_000L + it)) }
        assertFalse(limite.autoriser(30_000))
        assertTrue(limite.autoriser(61_000))
    }

    @Test
    fun fraicheurDesEtats() {
        val f = FraicheurEtats()
        val t = 1_792_300_000_000L
        assertTrue(f.accepter("a", t, t + 1_000))
        assertFalse(f.accepter("a", t, t + 1_000)) // rejoué
        assertFalse(f.accepter("a", t - 1, t + 1_000)) // plus ancien
        assertTrue(f.accepter("b", t, t + 1_000)) // par chaîne
        assertFalse(f.accepter("a", t + 2, t + 2 + FraicheurEtats.AGE_MAX_MS + 1)) // trop vieux
        assertEquals(1_200_000L, FraicheurEtats.AGE_MAX_MS)
    }

    @Test
    fun reconnaissanceAvecHorlogesDecalees() {
        val b = Crypto.cleBle(s)
        val r = Reconnaissance(mapOf("moi" to b))
        val frontiere = (w + 1) * Crypto.DUREE_FENETRE_BLE_MS
        // Chercheur 1 ms avant la frontière (fenêtre w), balise déjà passée à w + 1 : reconnue.
        r.maj(Crypto.fenetreBle(frontiere - 1))
        assertEquals(w, r.fenetreCalculee)
        assertEquals("moi", r.reconnaitre(Proximite.donneesService(Crypto.idBle(b, Crypto.fenetreBle(frontiere)))))
        // Chercheur juste après la frontière (w + 1), balise en retard restée sur w : reconnue.
        r.maj(Crypto.fenetreBle(frontiere))
        assertEquals(w + 1, r.fenetreCalculee)
        assertEquals("moi", r.reconnaitre(Proximite.donneesService(Crypto.idBle(b, w))))
        // Horloges décalées de 14 min 59 s dans les deux sens : toujours reconnue.
        val t = frontiere + 60_000L
        r.maj(Crypto.fenetreBle(t))
        for (decalage in listOf(-899_000L, 899_000L)) {
            assertEquals("moi", r.reconnaitre(Proximite.donneesService(Crypto.idBle(b, Crypto.fenetreBle(t + decalage)))))
        }
        // Rotation en retard de plus d'une fenêtre : plus reconnue.
        assertNull(r.reconnaitre(Proximite.donneesService(Crypto.idBle(b, Crypto.fenetreBle(t) - 2))))
        // Bonne valeur mais octet de version inconnu : ignorée.
        assertNull(r.reconnaitre(Crypto.idBle(b, Crypto.fenetreBle(t)) + 0x02))
    }

    @Test
    fun lissageSurUneSerieDeMesures() {
        val suivi = SuiviSignal()
        suivi.mesure(-90, 0)
        // Mesures à intervalle régulier : r suit la formule pas à pas et se rapproche de -60.
        var attendu = -90.0
        var precedent = Double.NEGATIVE_INFINITY
        for (i in 1..10) {
            val t = i * 500L
            suivi.mesure(-60, t)
            attendu += (1 - exp(-500.0 / Proximite.TAU_MS)) * (-60 - attendu)
            assertEquals(attendu, suivi.lisse!!, 1e-9)
            assertTrue("the smoothed signal rises", suivi.lisse!! > precedent)
            precedent = suivi.lisse!!
        }
        assertTrue("close to the target after 5 s (2.5 τ)", suivi.lisse!! > -63.0)
        // Deux mesures au même instant : α = 0, rien ne bouge.
        val avant = suivi.lisse!!
        suivi.mesure(-20, 5_000)
        assertEquals(avant, suivi.lisse!!, 1e-9)
        // Un pic isolé ne fait pas sauter la jauge : 250 ms après, il ne compte que pour 12 %.
        suivi.mesure(-100, 5_250)
        assertEquals(avant + (1 - exp(-250.0 / Proximite.TAU_MS)) * (-100 - avant), suivi.lisse!!, 1e-9)
        // Revue après plus de 10 s : le lissage repart de la nouvelle mesure.
        suivi.mesure(-80, 5_250 + Proximite.VU_RECEMMENT_MS + 1)
        assertEquals(-80.0, suivi.lisse!!, 1e-9)
    }

    // -----------------------------------------------------------------
    // Corrections de la revue de la 1.1 (mode recherche, serveur GATT, chercheur)
    // -----------------------------------------------------------------

    @Test
    fun modeRecherche() {
        val t = 10_000_000L
        // Rien : ni commande récente, ni sonnerie, relais joint.
        assertFalse(Proximite.modeRecherche(t, 0L, false, 0L))
        // 10 min après une commande acceptée.
        val fin = t + Proximite.DUREE_RECHERCHE_MS
        assertTrue(Proximite.modeRecherche(t, fin, false, 0L))
        assertTrue(Proximite.modeRecherche(fin - 1, fin, false, 0L))
        assertFalse(Proximite.modeRecherche(fin, fin, false, 0L))
        // Tant que le téléphone sonne.
        assertTrue(Proximite.modeRecherche(fin + 1, fin, true, 0L))
        // Relais injoignable : seulement après une heure.
        assertFalse(Proximite.modeRecherche(t + Proximite.HORS_LIGNE_AVANT_RECHERCHE_MS - 1, 0L, false, t))
        assertTrue(Proximite.modeRecherche(t + Proximite.HORS_LIGNE_AVANT_RECHERCHE_MS, 0L, false, t))
        // Le chercheur relance avant la fin du mode.
        assertTrue(Proximite.RELANCE_REVEIL_MS < Proximite.DUREE_RECHERCHE_MS)
    }

    @Test
    fun etatBourreATailleFixe() {
        val court = """{"ts":1792300000000,"ringing":false,"battery":7,"charging":false,"app":"1.1.0","ack":null,"model":"A"}"""
        val long = """{"ts":1792300000000,"ringing":true,"battery":100,"charging":true,"app":"1.1.0","ack":"${"a".repeat(32)}","model":"${"é".repeat(Proximite.MODELE_MAX)}"}"""
        for (clair in listOf(court, long)) {
            val bourre = Proximite.bourrerEtat(clair)
            assertEquals(Proximite.TAILLE_CLAIR_ETAT, bourre.toByteArray(Charsets.UTF_8).size)
            assertTrue(bourre.startsWith(clair.dropLast(1) + ",\"pad\":\""))
            assertTrue(bourre.endsWith(" \"}"))
        }
        assertEquals("""{"pad":"   "}""", Proximite.bourrerEtat("{}", 13))
        // Déjà trop long : rendu tel quel.
        val enorme = """{"model":"${"x".repeat(400)}"}"""
        assertEquals(enorme, Proximite.bourrerEtat(enorme))
        // Deux états de modèles différents ont des chiffrés de même longueur, et le message tient en 512 octets.
        val kEnc = Crypto.cleChiffrement(s)
        val id = Crypto.deriveId(s)
        val tailles = listOf(court, long).map { clair ->
            val c = Crypto.chiffrerEtat(kEnc, id, Proximite.bourrerEtat(clair))
            val message = Proximite.messageEtat(c.nonce, c.enc)
            assertEquals("""{"type":"state","nonce":"${c.nonce}","enc":"${c.enc}"}""", message)
            // Taille fixe, quel que soit le nombre de « / » du base64 (jamais échappés).
            assertEquals(508, message.toByteArray(Charsets.UTF_8).size)
            assertTrue(message.toByteArray(Charsets.UTF_8).size <= Proximite.TAILLE_MAX_MESSAGE)
            assertEquals(Proximite.bourrerEtat(clair), Crypto.dechiffrerEtat(kEnc, id, c.nonce, c.enc))
            c.enc.length
        }
        assertEquals(tailles[0], tailles[1])
    }

    @Test
    fun suiviParAdresseEtCopie() {
        val b = SuiviBalises()
        b.mesure("AA", -80, 0)
        assertTrue(b.vueRecemment(0))
        assertEquals(listOf("AA"), b.adresses(0))
        // Rotation : la nouvelle adresse prend le relais, l'ancienne se tait. Pas de copie signalée.
        b.mesure("BB", -78, 250)
        for (t in 500L..2_500L step 250) b.mesure("BB", -78, t)
        assertFalse(b.copieProbable(2_500))
        // L'ancienne adresse s'est tue : elle n'est plus essayée pour Faire sonner.
        assertEquals(listOf("BB"), b.adresses(2_500))
        // Plus rien d'entendu depuis 2 s : les adresses des 10 s, la plus récente d'abord.
        assertEquals(listOf("BB", "AA"), b.adresses(2_500 + Proximite.SIMULTANE_MS + 1))
        // Un clone fort émet le même id_ble en même temps : signalé après 3 s, la jauge suit le plus fort,
        // et les deux adresses restent essayables (la plus forte d'abord).
        var t = 3_000L
        while (t <= 7_000L) {
            b.mesure("BB", -78, t)
            b.mesure("CC", -50, t + 10)
            t += 250
        }
        assertTrue(b.copieProbable(7_010))
        assertEquals(-50, b.meilleur(7_010)!!.derniereMesure)
        assertEquals(listOf("CC", "BB"), b.adresses(7_010).take(2))
        // Au début de la copie, pas encore d'alerte.
        val c = SuiviBalises()
        c.mesure("BB", -78, 0)
        c.mesure("CC", -50, 10)
        assertFalse(c.copieProbable(10))
        // Les adresses perdues (plus de 10 s) sont oubliées.
        b.mesure("CC", -50, 7_010 + Proximite.VU_RECEMMENT_MS + 1)
        assertEquals(listOf("CC"), b.adresses(7_010 + Proximite.VU_RECEMMENT_MS + 1))
        b.vider()
        assertFalse(b.vueRecemment(0))
    }

    @Test
    fun demarragesDeScan() {
        val h = HistoriqueDemarrages()
        // 4 démarrages de suite sans attente, puis on attend que le plus ancien sorte des 30 s.
        repeat(4) {
            assertEquals(0L, h.attente(1_000L + it))
            h.noter(1_000L + it)
        }
        assertEquals(30_000L - 4_000L, h.attente(5_000L))
        assertEquals(0L, h.attente(31_000L))
    }

    @Test
    fun evictionDesConnexions() {
        data class C(val nom: String, val debut: Long, val valide: Boolean)
        val liste = listOf(C("a", 10, true), C("b", 20, false), C("c", 5, false), C("d", 1, true))
        // La plus ancienne SANS commande valide.
        assertEquals("c", aEvincer(liste, { it.debut }, { it.valide })?.nom)
        // Toutes ont une commande valide : personne n'est évincé, le nouveau venu est refusé.
        assertNull(aEvincer(liste.filter { it.valide }, { it.debut }, { it.valide }))
    }

    @Test
    fun reessaiGatt() {
        assertTrue(Proximite.connexionReessayable(133))
        assertTrue(Proximite.connexionReessayable(62))
        assertTrue(Proximite.connexionReessayable(8))
        assertFalse(Proximite.connexionReessayable(0))
        assertFalse(Proximite.connexionReessayable(19))
        assertTrue(Proximite.REESSAI_AVANT_MS + Proximite.PAUSE_REESSAI_MS < 5_000L)
    }

    @Test
    fun memoireDesNoncesReecriteSeulementSiElleChange() {
        val kSig = Crypto.cleSignature(s)
        val cles = FiltreCommandes.Cles(Crypto.deriveId(s), kSig, Crypto.cleCommande(kSig))
        val ts = 1_792_300_000_000L
        val filtre = FiltreCommandes()
        val r0 = filtre.revision
        // Mal signée : aucune écriture à faire.
        val fausse = JSONObject(Crypto.messageCommandeV2(kSig, "ring", ts, "00".repeat(16))).put("sig", "00".repeat(32))
        assertEquals(FiltreCommandes.Issue.Refusee("signature"), filtre.accepter(cles, fausse, ts))
        assertEquals(r0, filtre.revision)
        // Valide : la mémoire change.
        val ring = JSONObject(Crypto.chiffrerCommande(cles.kCmd, cles.id, "ring", ts))
        assertTrue(filtre.accepter(cles, ring, ts) is FiltreCommandes.Issue.Acceptee)
        val r1 = filtre.revision
        assertTrue(r1 != r0)
        // Rejouée : refusée, rien ne change, et le serveur GATT la tient pour invalide.
        assertEquals("rejeu", filtre.recevable(cles, ring, ts))
        assertEquals(FiltreCommandes.Issue.Refusee("rejeu"), filtre.accepter(cles, ring, ts))
        assertEquals(r1, filtre.revision)
        // Un nonce v2 écrit en majuscules est retenu en minuscules : le même, rejoué en minuscules, est refusé.
        val nonce = "AB".repeat(16)
        assertTrue(filtre.accepter(cles, JSONObject(Crypto.messageCommandeV2(kSig, "ping", ts, nonce)), ts) is FiltreCommandes.Issue.Acceptee)
        assertTrue(nonce.lowercase() in filtre.nonces())
        assertEquals("rejeu", filtre.recevable(cles, JSONObject(Crypto.messageCommandeV2(kSig, "ping", ts, nonce.lowercase())), ts))
    }
}
