package dev.sidali.sonne

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import org.json.JSONObject

/**
 * Cryptographie de Sonne, conforme à docs/PROTOCOLE.md et à relay/crypto.js.
 * Fonctions pures, sans dépendance Android : testables sur la JVM.
 */
object Crypto {

    /** Taille du secret S en octets (128 bits). */
    const val TAILLE_SECRET = 16

    /** Écart maximal accepté entre l'horodatage d'une commande et l'horloge du téléphone. */
    const val FENETRE_MS = 15L * 60 * 1000

    /** Commandes reconnues. */
    val COMMANDES = setOf("ring", "stop", "ping")

    private val alea = SecureRandom()

    // ---------------------------------------------------------------------
    // Outils
    // ---------------------------------------------------------------------

    fun hex(octets: ByteArray): String {
        val sb = StringBuilder(octets.size * 2)
        for (b in octets) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xf])
        }
        return sb.toString()
    }

    /** Hex vers octets. Lève IllegalArgumentException si la chaîne n'est pas de l'hex pair. */
    fun depuisHex(texte: String): ByteArray {
        require(texte.length % 2 == 0) { "Hex of odd length" }
        return ByteArray(texte.length / 2) { i ->
            val haut = Character.digit(texte[2 * i], 16)
            val bas = Character.digit(texte[2 * i + 1], 16)
            require(haut >= 0 && bas >= 0) { "Non-hex character" }
            ((haut shl 4) or bas).toByte()
        }
    }

    fun aleatoire(taille: Int): ByteArray = ByteArray(taille).also { alea.nextBytes(it) }

    fun sha256(vararg morceaux: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (m in morceaux) md.update(m)
        return md.digest()
    }

    fun hmacSha256(cle: ByteArray, donnees: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cle, "HmacSHA256"))
        return mac.doFinal(donnees)
    }

    /** HKDF-SHA256 (RFC 5869). Un sel vide équivaut à 32 octets nuls. */
    fun hkdf(ikm: ByteArray, sel: ByteArray, info: ByteArray, longueur: Int): ByteArray {
        val prk = hmacSha256(if (sel.isEmpty()) ByteArray(32) else sel, ikm)
        val sortie = ByteArray(longueur)
        var precedent = ByteArray(0)
        var rempli = 0
        var compteur = 1
        while (rempli < longueur) {
            precedent = hmacSha256(prk, precedent + info + byteArrayOf(compteur.toByte()))
            val n = minOf(precedent.size, longueur - rempli)
            System.arraycopy(precedent, 0, sortie, rempli, n)
            rempli += n
            compteur++
        }
        return sortie
    }

    /** Comparaison en temps constant. */
    fun egaux(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---------------------------------------------------------------------
    // Secret, identifiant et clés
    // ---------------------------------------------------------------------

    fun genererSecret(): ByteArray = aleatoire(TAILLE_SECRET)

    private fun verifierSecret(s: ByteArray) {
        require(s.size == TAILLE_SECRET) { "Invalid key: $TAILLE_SECRET bytes expected" }
    }

    /** Taille de la preuve de téléphone P en octets. */
    const val TAILLE_PREUVE = 16

    /**
     * Preuve de téléphone P = HKDF-SHA256(S, sel vide, "sonne-phone", 16) (protocole v2).
     * Présentée au relais à la connexion : elle prouve que l'on détient S sans révéler S ni les clés.
     */
    fun preuve(s: ByteArray): ByteArray {
        verifierSecret(s)
        return hkdf(s, ByteArray(0), "sonne-phone".toByteArray(Charsets.UTF_8), TAILLE_PREUVE)
    }

    /** id = hex(SHA-256("sonne-id" ‖ P))[0:32] : le relais le recalcule depuis P. */
    fun idDepuisPreuve(p: ByteArray): String {
        require(p.size == TAILLE_PREUVE) { "Invalid proof: $TAILLE_PREUVE bytes expected" }
        return hex(sha256("sonne-id".toByteArray(Charsets.UTF_8), p)).substring(0, 32)
    }

    /** id = hex(SHA-256("sonne-id" ‖ P))[0:32], avec P dérivée de S. */
    fun deriveId(s: ByteArray): String = idDepuisPreuve(preuve(s))

    /** K_sig = HKDF-SHA256(S, sel vide, "sonne-sig", 32). */
    fun cleSignature(s: ByteArray): ByteArray {
        verifierSecret(s)
        return hkdf(s, ByteArray(0), "sonne-sig".toByteArray(Charsets.UTF_8), 32)
    }

    /** K_enc = HKDF-SHA256(S, sel vide, "sonne-enc", 32). */
    fun cleChiffrement(s: ByteArray): ByteArray {
        verifierSecret(s)
        return hkdf(s, ByteArray(0), "sonne-enc".toByteArray(Charsets.UTF_8), 32)
    }

    // ---------------------------------------------------------------------
    // Proximité Bluetooth (v1.1) : identifiant tournant de la balise
    // ---------------------------------------------------------------------

    /** Durée d'une fenêtre de la balise : 15 min. L'identifiant change à chaque fenêtre. */
    const val DUREE_FENETRE_BLE_MS = 900_000L

    /** Taille de l'identifiant tournant id_ble en octets. */
    const val TAILLE_ID_BLE = 8

    /** Fenêtre de la balise : w = floor(now_ms / 900000). */
    fun fenetreBle(maintenantMs: Long): Long {
        require(maintenantMs >= 0) { "Invalid timestamp" }
        return maintenantMs / DUREE_FENETRE_BLE_MS
    }

    /** Clé de balise B = HKDF-SHA256(S, sel vide, "sonne-ble", 32). */
    fun cleBle(s: ByteArray): ByteArray {
        verifierSecret(s)
        return hkdf(s, ByteArray(0), "sonne-ble".toByteArray(Charsets.UTF_8), 32)
    }

    /** id_ble = HMAC-SHA256(B, w en 8 octets big-endian)[0:8], à partir de la clé de balise B. */
    fun idBle(b: ByteArray, w: Long): ByteArray {
        require(b.size == 32) { "32-byte beacon key expected" }
        require(w >= 0) { "Invalid window" }
        val fenetre = java.nio.ByteBuffer.allocate(8).putLong(w).array() // ByteBuffer est big-endian par défaut
        return hmacSha256(b, fenetre).copyOf(TAILLE_ID_BLE)
    }

    /** id_ble pour le secret S et la fenêtre w (voir relay/crypto.js, deriveBleId). */
    fun deriveBleId(s: ByteArray, w: Long): ByteArray = idBle(cleBle(s), w)

    // ---------------------------------------------------------------------
    // Commandes signées
    // ---------------------------------------------------------------------

    /** Texte signé : cmd + "|" + ts + "|" + nonce. */
    fun texteASigner(cmd: String, ts: Long, nonceHex: String): String = "$cmd|$ts|$nonceHex"

    /** sig = hex(HMAC-SHA256(K_sig, texte)). */
    fun signer(kSig: ByteArray, cmd: String, ts: Long, nonceHex: String): String =
        hex(hmacSha256(kSig, texteASigner(cmd, ts, nonceHex).toByteArray(Charsets.UTF_8)))

    private val reNonceCmd = Regex("^[0-9a-fA-F]{32}$")
    private val reSig = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Vérifie la forme et la signature d'une commande. Ne lève jamais.
     * Le nonce est signé tel qu'il est écrit dans le message.
     */
    fun verifierSignature(kSig: ByteArray, cmd: String, ts: Long, nonceHex: String, sigHex: String): Boolean {
        return try {
            if (cmd !in COMMANDES || ts < 0) return false
            if (!reNonceCmd.matches(nonceHex) || !reSig.matches(sigHex)) return false
            val attendue = hmacSha256(kSig, texteASigner(cmd, ts, nonceHex).toByteArray(Charsets.UTF_8))
            egaux(attendue, depuisHex(sigHex))
        } catch (e: Exception) {
            false
        }
    }

    /** Message exact d'une commande v2 signée (pas de JSONObject : rien à échapper, ordre fixe). */
    fun messageCommandeV2(kSig: ByteArray, cmd: String, ts: Long, nonceHex: String): String {
        require(cmd in COMMANDES) { "Unknown command: $cmd" }
        require(ts in 0..TS_MAX) { "Invalid timestamp" }
        require(reNonceCmd.matches(nonceHex)) { "16-byte hex nonce expected" }
        return "{\"type\":\"cmd\",\"cmd\":\"$cmd\",\"ts\":$ts,\"nonce\":\"$nonceHex\",\"sig\":\"${signer(kSig, cmd, ts, nonceHex)}\"}"
    }

    // ---------------------------------------------------------------------
    // Protocole v3 : commandes chiffrées (PROTOCOLE.md, « Protocole v3 : commandes chiffrées »)
    //
    // Le relais et un observateur Bluetooth ne voient plus qu'un message de taille fixe :
    //   {"type":"cmd","v":3,"nonce":"<12 octets en hex>","enc":"<base64 de 80 octets>"}  (172 octets)
    // enc = AES-256-GCM(K_cmd, iv = nonce, aad = "sonne-cmd-v3|" + id, clair de 64 octets).
    // K_cmd = HKDF-Expand(K_sig, "sonne-cmd-v3", 32) : « Mes autres téléphones » la calcule sans S.
    // ---------------------------------------------------------------------

    /**
     * Transition (app 1.6) : le téléphone accepte encore les commandes v2 signées, et le
     * chercheur en envoie aux chaînes qui n'ont pas annoncé cmdv ≥ 3. Passe à false à la
     * coupure (app 1.8, jamais avant que le relais et la page 1.7 soient déployés) : v2 refusée
     * (« ancien-format »), écritures GATT de 172 octets exactement, v3 toujours envoyée.
     * Réglé dans app/build.gradle.kts (ACCEPTER_V2).
     */
    val ACCEPTER_V2: Boolean = BuildConfig.ACCEPTER_V2

    /** Version du format chiffré : champ v du message et octet 0 du clair. */
    const val VERSION_COMMANDE = 3

    /** Taille fixe du clair d'une commande v3. */
    const val TAILLE_CLAIR_COMMANDE = 64

    /** Taille de enc décodé : clair suivi de l'étiquette GCM de 16 octets. */
    const val TAILLE_ENC_COMMANDE = TAILLE_CLAIR_COMMANDE + 16

    /** Longueur de enc en base64 standard : 107 caractères puis un seul « = ». */
    const val LONGUEUR_ENC_COMMANDE = 108

    /** Taille exacte du message d'une commande v3, quel que soit son contenu. */
    const val TAILLE_MESSAGE_COMMANDE = 172

    /** Nonce d'une commande v3 : IV AES-GCM, identifiant d'anti rejeu et valeur de l'ack. */
    const val TAILLE_NONCE_COMMANDE = 12

    /** Nonces valables retenus au plus ; au-delà, les nouvelles commandes sont refusées. */
    const val CAPACITE_NONCES = 2000

    /** Plus grand ts lisible (2^53 - 1, comme Number.MAX_SAFE_INTEGER de la page et du relais). */
    const val TS_MAX = 9_007_199_254_740_991L

    /** Codes de commande (octet 1 du clair). « ping » est la vérification d'état. */
    val CODES_COMMANDE = mapOf("ring" to 1, "stop" to 2, "ping" to 3)

    /** Durée de sonnerie demandée : 0 = réglage du téléphone, sinon 60 à 600 s. */
    const val DUREE_MIN_S = 60
    const val DUREE_MAX_S = 600

    /** Lampe demandée : réglage du téléphone, allumée, éteinte. */
    const val LAMPE_REGLAGE = 0
    const val LAMPE_ALLUMEE = 1
    const val LAMPE_ETEINTE = 2

    /** Libellé HKDF de K_cmd et préfixe de l'AAD. */
    const val INFO_CLE_COMMANDE = "sonne-cmd-v3"
    const val PREFIXE_AAD_COMMANDE = "sonne-cmd-v3|"

    /** Champ de l'état (clair chiffré) qui annonce les commandes v3. */
    const val CHAMP_CAPACITE = "cmdv"

    private val reId = Regex("^[0-9a-f]{32}$")
    private val reNonceV3 = Regex("^[0-9a-f]{24}$")
    private val reBase64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val reEncV3 = Regex("^[A-Za-z0-9+/]{107}=$")

    /** Refus d'une commande, avec sa raison (celle des cas de docs/VECTEURS.json). */
    class Refus(val raison: String) : Exception(raison)

    /** Commande lue (v2 vérifiée ou v3 déchiffrée), avant la fenêtre et l'anti rejeu. */
    data class CommandeLue(
        val format: Int,
        val cmd: String,
        val ts: Long,
        /** Nonce en hex minuscule : 32 caractères en v2, 24 en v3. C'est aussi l'ack. */
        val nonce: String,
        val duree: Int = 0,
        val lampe: Int = 0,
    )

    /**
     * HKDF-Expand (RFC 5869, section 2.3) avec SHA-256, sans l'étape Extract : K_sig est déjà
     * une clé uniforme de 32 octets (sortie de HKDF), cas prévu par la RFC 5869, section 3.3.
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, longueur: Int): ByteArray {
        require(longueur in 1..255 * 32) { "Invalid HKDF length" }
        val sortie = ByteArray(longueur)
        var precedent = ByteArray(0)
        var rempli = 0
        var compteur = 1
        while (rempli < longueur) {
            precedent = hmacSha256(prk, precedent + info + byteArrayOf(compteur.toByte()))
            val n = minOf(precedent.size, longueur - rempli)
            System.arraycopy(precedent, 0, sortie, rempli, n)
            rempli += n
            compteur++
        }
        return sortie
    }

    /** K_cmd = HKDF-Expand(K_sig, "sonne-cmd-v3", 32) = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01). */
    fun cleCommande(kSig: ByteArray): ByteArray {
        require(kSig.size == 32) { "32-byte K_sig expected" }
        return hkdfExpand(kSig, INFO_CLE_COMMANDE.toByteArray(Charsets.UTF_8), 32)
    }

    /** AAD d'une commande v3 : "sonne-cmd-v3|" + id, 45 octets ASCII. */
    fun aadCommande(id: String): ByteArray {
        require(reId.matches(id)) { "Invalid id" }
        return (PREFIXE_AAD_COMMANDE + id).toByteArray(Charsets.UTF_8)
    }

    /**
     * Clair d'une commande v3, 64 octets : version, code, ts (8 octets big-endian), durée
     * (2 octets big-endian), lampe, puis 51 octets à zéro. Applique les règles d'envoi :
     * durée 0 ou 60 à 600 s, lampe 0, 1 ou 2, tous deux à 0 pour stop et ping.
     */
    fun clairCommande(cmd: String, ts: Long, duree: Int = 0, lampe: Int = 0): ByteArray {
        val code = CODES_COMMANDE[cmd] ?: throw IllegalArgumentException("Unknown command: $cmd")
        require(ts in 0..TS_MAX) { "Invalid timestamp" }
        require(duree == 0 || duree in DUREE_MIN_S..DUREE_MAX_S) { "Duration: 0 or $DUREE_MIN_S to $DUREE_MAX_S s" }
        require(lampe in LAMPE_REGLAGE..LAMPE_ETEINTE) { "Flashlight: 0, 1 or 2" }
        require(cmd == "ring" || (duree == 0 && lampe == 0)) { "Duration and flashlight only apply to ring" }
        return java.nio.ByteBuffer.allocate(TAILLE_CLAIR_COMMANDE) // big-endian, rempli de zéros
            .put(VERSION_COMMANDE.toByte())
            .put(code.toByte())
            .putLong(ts)
            .putShort(duree.toShort())
            .put(lampe.toByte())
            .array()
    }

    /**
     * Message exact d'une commande v3, écrit à la main, sans espace, dans l'ordre du
     * protocole : toujours 172 octets. JSONObject échapperait chaque « / » du base64.
     */
    fun messageCommande(nonceHex: String, enc: String): String {
        require(reNonceV3.matches(nonceHex)) { "12-byte lowercase hex nonce expected" }
        require(reEncV3.matches(enc)) { "108-character base64 expected" }
        return "{\"type\":\"cmd\",\"v\":$VERSION_COMMANDE,\"nonce\":\"$nonceHex\",\"enc\":\"$enc\"}"
    }

    /** Chiffre un clair de 64 octets pour le téléphone d'identifiant id : le message de 172 octets. */
    fun chiffrerClairCommande(kCmd: ByteArray, id: String, clair: ByteArray, nonce: ByteArray): String {
        require(kCmd.size == 32) { "32-byte K_cmd expected" }
        require(nonce.size == TAILLE_NONCE_COMMANDE) { "12-byte nonce expected" }
        require(clair.size == TAILLE_CLAIR_COMMANDE) { "64-byte plaintext expected" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kCmd, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aadCommande(id))
        val enc = c.doFinal(clair) // javax.crypto ajoute l'étiquette à la fin
        return messageCommande(hex(nonce), Base64.getEncoder().encodeToString(enc))
    }

    /**
     * Commande v3 chiffrée avec K_cmd pour le téléphone d'identifiant id (le message exact,
     * 172 octets). Le nonce est tiré au hasard par défaut ; il est aussi l'ack attendu.
     */
    fun chiffrerCommande(
        kCmd: ByteArray,
        id: String,
        cmd: String,
        ts: Long,
        nonce: ByteArray = aleatoire(TAILLE_NONCE_COMMANDE),
        duree: Int = 0,
        lampe: Int = 0,
    ): String = chiffrerClairCommande(kCmd, id, clairCommande(cmd, ts, duree, lampe), nonce)

    /**
     * Déchiffre une commande v3 dont la forme (type, v) a été vue par l'appelant. Vérifie dans
     * l'ordre du protocole : forme du nonce et de enc, taille, authentification (AAD liée à
     * l'id du téléphone), version, commande, ts lisible (au plus 2^53 - 1, testé avant tout
     * calcul : un ts au bit de poids fort est négatif dans un Long). Lève Refus sinon.
     * Ne contrôle ni la fenêtre de 15 min ni le rejeu.
     */
    fun dechiffrerCommande(kCmd: ByteArray, id: String, nonceHex: Any?, enc: Any?): CommandeLue {
        if (nonceHex !is String || !reNonceV3.matches(nonceHex)) throw Refus("forme")
        if (enc !is String || !reBase64.matches(enc)) throw Refus("forme")
        if (!reEncV3.matches(enc)) throw Refus("taille")
        val octets = try {
            Base64.getDecoder().decode(enc)
        } catch (e: IllegalArgumentException) {
            throw Refus("taille")
        }
        if (octets.size != TAILLE_ENC_COMMANDE) throw Refus("taille")
        val clair = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kCmd, "AES"), GCMParameterSpec(128, depuisHex(nonceHex)))
            c.updateAAD(aadCommande(id))
            c.doFinal(octets)
        } catch (e: Exception) {
            // Étiquette refusée (autre chaîne, mauvaise clé, octet ou nonce altéré) ou clé inutilisable.
            throw Refus("authentification")
        }
        try {
            if (clair.size != TAILLE_CLAIR_COMMANDE) throw Refus("taille")
            if (clair[0].toInt() != VERSION_COMMANDE) throw Refus("version")
            val code = clair[1].toInt() and 0xff
            val cmd = CODES_COMMANDE.entries.firstOrNull { it.value == code }?.key ?: throw Refus("commande")
            val tampon = java.nio.ByteBuffer.wrap(clair)
            val ts = tampon.getLong(2)
            // Non signé sur 64 bits : négatif ici si le bit de poids fort est à 1.
            if (ts < 0 || ts > TS_MAX) throw Refus("fraicheur")
            val duree = tampon.getShort(10).toInt() and 0xffff
            val lampe = clair[12].toInt() and 0xff
            return CommandeLue(VERSION_COMMANDE, cmd, ts, nonceHex, duree, lampe)
        } finally {
            clair.fill(0)
        }
    }

    /**
     * Vrai si la valeur du champ cmdv d'un état déchiffré annonce les commandes v3 : un
     * entier au moins égal à 3 (comme accepteV3 de relay/crypto.js ; la chaîne "3" ne compte pas).
     */
    fun accepteV3(cmdv: Any?): Boolean = when (cmdv) {
        is Int -> cmdv >= VERSION_COMMANDE
        is Long -> cmdv >= VERSION_COMMANDE
        is Number -> {
            val d = cmdv.toDouble()
            d.isFinite() && d == Math.floor(d) && d >= VERSION_COMMANDE
        }
        else -> false
    }

    /** Ce que le téléphone applique pour une ring : null veut dire « son réglage ». */
    data class ParametresSonnerie(val dureeS: Int? = null, val lampe: Boolean? = null)

    /**
     * Paramètres d'une ring (parametresSonnerie de relay/crypto.js) : une durée hors de
     * 60 à 600 s ou une lampe inconnue retombent sur le réglage, sans jamais refuser la
     * commande (un émetteur maladroit ne doit pas empêcher la sonnerie).
     */
    fun parametresSonnerie(duree: Int, lampe: Int): ParametresSonnerie = ParametresSonnerie(
        dureeS = if (duree in DUREE_MIN_S..DUREE_MAX_S) duree else null,
        lampe = when (lampe) {
            LAMPE_ALLUMEE -> true
            LAMPE_ETEINTE -> false
            else -> null
        },
    )

    // ---------------------------------------------------------------------
    // États chiffrés
    // ---------------------------------------------------------------------

    /** Résultat d'un chiffrement d'état : nonce en hex (12 octets) et enc en base64 standard. */
    data class EtatChiffre(val nonce: String, val enc: String)

    /** AES-256-GCM(K_enc, iv = nonce, aad = id en UTF-8). enc = base64(chiffré ‖ étiquette). */
    fun chiffrerEtat(kEnc: ByteArray, id: String, clair: String, nonce: ByteArray = aleatoire(12)): EtatChiffre {
        require(nonce.size == 12) { "12-byte nonce expected" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kEnc, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(id.toByteArray(Charsets.UTF_8))
        val sortie = c.doFinal(clair.toByteArray(Charsets.UTF_8)) // javax.crypto ajoute l'étiquette à la fin
        return EtatChiffre(hex(nonce), Base64.getEncoder().encodeToString(sortie))
    }

    /** Inverse de chiffrerEtat. Lève une exception si la clé, l'id ou le contenu ne correspondent pas. */
    fun dechiffrerEtat(kEnc: ByteArray, id: String, nonceHex: String, enc: String): String {
        val iv = depuisHex(nonceHex)
        require(iv.size == 12) { "12-byte nonce expected" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kEnc, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(id.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(Base64.getDecoder().decode(enc)), Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------
    // 12 mots BIP39 (128 bits + 4 bits de somme de contrôle)
    // ---------------------------------------------------------------------

    /** S vers 12 mots, avec la liste anglaise officielle de 2048 mots. */
    fun versMots(s: ByteArray, liste: List<String>): List<String> {
        verifierSecret(s)
        require(liste.size == 2048) { "2048-word BIP39 list expected" }
        val controle = (sha256(s)[0].toInt() and 0xff) shr 4
        // 132 bits : S (128 bits) suivi des 4 bits de contrôle, découpés en 12 tranches de 11 bits.
        var bits = java.math.BigInteger(1, s).shiftLeft(4).or(java.math.BigInteger.valueOf(controle.toLong()))
        val mots = ArrayList<String>(12)
        val masque = java.math.BigInteger.valueOf(0x7ff)
        for (i in 11 downTo 0) {
            mots.add(liste[bits.shiftRight(i * 11).and(masque).toInt()])
        }
        return mots
    }

    /** 12 mots vers S. Lève IllegalArgumentException si un mot est inconnu ou si la somme de contrôle est fausse. */
    fun depuisMots(texte: String, liste: List<String>): ByteArray {
        val mots = texte.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(mots.size == 12) { "Exactly 12 words expected" }
        var bits = java.math.BigInteger.ZERO
        for (mot in mots) {
            val index = liste.indexOf(mot)
            require(index >= 0) { "Unknown word: $mot" }
            bits = bits.shiftLeft(11).or(java.math.BigInteger.valueOf(index.toLong()))
        }
        val controle = bits.and(java.math.BigInteger.valueOf(0xf)).toInt()
        val brut = bits.shiftRight(4).toByteArray()
        val s = ByteArray(TAILLE_SECRET)
        // BigInteger peut ajouter un octet de signe ou en retirer en tête : on recale à droite.
        val n = minOf(brut.size, TAILLE_SECRET)
        System.arraycopy(brut, brut.size - n, s, TAILLE_SECRET - n, n)
        require(((sha256(s)[0].toInt() and 0xff) shr 4) == controle) { "Wrong checksum" }
        return s
    }

    // ---------------------------------------------------------------------
    // Lien d'appairage
    // ---------------------------------------------------------------------

    /** S vers lien : <base>/#s=<base64url(S) sans remplissage>. */
    fun versLien(s: ByteArray, base: String): String {
        verifierSecret(s)
        val racine = if (base.endsWith("/")) base else "$base/"
        return racine + "#s=" + Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    private val reBase64Url = Regex("^[A-Za-z0-9_-]+={0,2}$")

    /** Lien d'appairage vers S (comme fromLink dans relay/crypto.js). Lève IllegalArgumentException si mal formé. */
    fun depuisLien(texte: String): ByteArray {
        val t = texte.trim()
        val diese = t.indexOf('#')
        require(diese >= 0) { "Invalid link: no fragment" }
        val valeur = t.substring(diese + 1).split('&')
            .firstOrNull { it.startsWith("s=") }?.removePrefix("s=")
        require(valeur != null && reBase64Url.matches(valeur)) { "Invalid link: s parameter missing or malformed" }
        val s = try {
            Base64.getUrlDecoder().decode(valeur.trimEnd('='))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid link: unreadable base64url")
        }
        require(s.size == TAILLE_SECRET) { "Invalid link: key of the wrong size" }
        return s
    }
}


/**
 * Filtre des commandes reçues (PROTOCOLE.md, « Acceptation par le téléphone »), v2 et v3 :
 * forme, signature (v2) ou authentification GCM liée à l'id (v3), fraîcheur (15 min), nonces
 * déjà vus, capacité et ordre. Une seule mémoire de nonces et un seul dernierTsOrdre pour les
 * deux formats, pour le relais et le Bluetooth : une commande reçue par l'un puis par l'autre
 * n'est exécutée qu'une fois. Référence : creerFiltreCommandes dans relay/crypto.js.
 *
 * Anti rejeu par l'âge, pas par le nombre : un nonce est gardé tant que le ts de sa commande
 * est dans la fenêtre de 15 min, donc tant qu'un rejeu serait encore accepté. Au-delà de
 * `capacite` nonces valables à la fois, les nouvelles commandes sont refusées plutôt que
 * d'oublier un nonce encore rejouable.
 *
 * Ordre : une ring ou une stop plus ancienne (ts) que la dernière ring ou stop acceptée est
 * ignorée (son nonce est quand même retenu). Un relais ne peut donc pas faire resonner le
 * téléphone en relivrant une vieille ring après une stop. Le ping n'est pas concerné.
 *
 * Sans dépendance Android (org.json mis à part), pour être testé sur la JVM.
 *
 * @param accepterV2 true pendant la transition (app 1.6), false après la coupure (« ancien-format »).
 */
class FiltreCommandes(
    nonces: Map<String, Long> = emptyMap(),
    dernierTsOrdre: Long = 0L,
    private val capacite: Int = Crypto.CAPACITE_NONCES,
    private val accepterV2: Boolean = Crypto.ACCEPTER_V2,
) {

    /** Ce que le téléphone sait de sa chaîne : son id (AAD), K_sig (v2) et K_cmd (v3). */
    class Cles(val id: String, val kSig: ByteArray, val kCmd: ByteArray)

    /** Issue d'une commande soumise au filtre. */
    sealed class Issue {
        data class Acceptee(val commande: Crypto.CommandeLue) : Issue()
        data class Refusee(val raison: String) : Issue()
    }

    /** nonce (hex minuscule) vers ts de la commande, du plus ancien accepté au plus récent. */
    private val vus = LinkedHashMap<String, Long>().apply {
        // Un ts illisible (mémoire abîmée) ne pourrait jamais être purgé : on l'oublie.
        nonces.forEach { (n, ts) -> if (ts in 0..Crypto.TS_MAX) put(n.lowercase(), ts) }
    }

    /** ts de la dernière ring ou stop acceptée (à sauvegarder avec les nonces). */
    var dernierTsOrdre: Long = dernierTsOrdre
        private set

    /**
     * Change à chaque modification des nonces retenus ou de dernierTsOrdre : on ne réécrit la
     * mémoire sur disque que dans ce cas (une commande mal signée ne coûte pas d'écriture).
     */
    @Volatile
    var revision = 0
        private set

    /** Nonces retenus avec leur ts (pour les sauvegarder). */
    @Synchronized
    fun nonces(): Map<String, Long> = LinkedHashMap(vus)

    /**
     * Étapes 1 à 6 du protocole, sans état : forme, puis signature (v2) ou taille,
     * authentification, version et commande (v3), puis fraîcheur. Lève Crypto.Refus.
     */
    fun lire(cles: Cles, message: JSONObject, maintenant: Long): Crypto.CommandeLue {
        if (message.opt("type") != "cmd") throw Crypto.Refus("forme")
        val lu = if (!message.has("v")) {
            // Sans champ v : commande v2 signée.
            if (!accepterV2) throw Crypto.Refus("ancien-format")
            val cmd = message.opt("cmd") as? String
            val ts = entier(message.opt("ts"))
            val nonce = message.opt("nonce") as? String
            val sig = message.opt("sig") as? String
            if (cmd == null || ts == null || nonce == null || sig == null ||
                !Crypto.verifierSignature(cles.kSig, cmd, ts, nonce, sig)
            ) {
                throw Crypto.Refus("signature")
            }
            Crypto.CommandeLue(2, cmd, ts, nonce.lowercase())
        } else {
            val v = message.opt("v")
            // Comme la page et le relais (v === 3) : le nombre 3, pas la chaîne "3".
            if (v !is Number || v.toDouble() != Crypto.VERSION_COMMANDE.toDouble()) throw Crypto.Refus("forme")
            Crypto.dechiffrerCommande(cles.kCmd, cles.id, message.opt("nonce"), message.opt("enc"))
        }
        // lu.ts est entre 0 et 2^53 - 1 : la différence ne déborde pas.
        if (abs(maintenant - lu.ts) > Crypto.FENETRE_MS) throw Crypto.Refus("fraicheur")
        return lu
    }

    /**
     * Écriture GATT : étapes 1 à 7 (jusqu'au rejeu compris) sans rien modifier, avant la limite
     * de débit. null si la commande est recevable, sinon la raison du refus.
     */
    @Synchronized
    fun recevable(cles: Cles, message: JSONObject, maintenant: Long): String? {
        val lu = try {
            lire(cles, message, maintenant)
        } catch (e: Crypto.Refus) {
            return e.raison
        }
        return if (lu.nonce in vus) "rejeu" else null
    }

    /** Soumet une commande : acceptée (à exécuter) ou refusée avec sa raison. */
    @Synchronized
    fun accepter(cles: Cles, message: JSONObject, maintenant: Long): Issue {
        val lu = try {
            lire(cles, message, maintenant)
        } catch (e: Crypto.Refus) {
            return Issue.Refusee(e.raison)
        }
        // On n'oublie que les nonces dont la commande ne pourrait plus être acceptée de toute façon.
        if (vus.entries.removeIf { abs(maintenant - it.value) > Crypto.FENETRE_MS }) revision++
        if (lu.nonce in vus) return Issue.Refusee("rejeu")
        if (vus.size >= capacite) return Issue.Refusee("capacite")
        vus[lu.nonce] = lu.ts
        revision++
        if (lu.cmd == "ring" || lu.cmd == "stop") {
            // Nonce retenu même si l'ordre la refuse : elle ne pourra pas revenir plus tard.
            if (lu.ts < dernierTsOrdre) return Issue.Refusee("ordre")
            dernierTsOrdre = lu.ts
        }
        return Issue.Acceptee(lu)
    }

    private companion object {
        /**
         * ts d'une commande v2 : un entier de 0 à 2^53 - 1, comme Number.isSafeInteger de la
         * page et du relais (un nombre écrit 1.7923e12 compte, 1.5 non). null sinon.
         */
        fun entier(v: Any?): Long? {
            val n: Long? = when (v) {
                is Int -> v.toLong()
                is Long -> v
                is Short -> v.toLong()
                is Byte -> v.toLong()
                is java.math.BigInteger -> if (v.bitLength() < 63) v.toLong() else null
                is java.math.BigDecimal -> try {
                    v.longValueExact()
                } catch (_: ArithmeticException) {
                    null
                }
                is Number -> {
                    val d = v.toDouble()
                    if (d.isFinite() && d == Math.floor(d) && abs(d) <= Crypto.TS_MAX.toDouble()) d.toLong() else null
                }
                else -> null
            }
            return n?.takeIf { it in 0..Crypto.TS_MAX }
        }
    }
}
