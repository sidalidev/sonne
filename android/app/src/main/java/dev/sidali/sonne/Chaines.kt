package dev.sidali.sonne

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Une chaîne connue d'un autre téléphone (« Mes autres téléphones ») : un nom choisi ici,
 * l'adresse du relais par lequel le joindre, et seulement les clés dont le chercheur a besoin
 * (id, K_sig, K_enc, B), dérivées comme dans la page web. S n'est pas gardé : sans lui, ni la
 * preuve P (se faire passer pour le téléphone auprès du relais) ni le lien d'appairage ne
 * peuvent être reconstitués depuis ce téléphone. K_cmd (commandes chiffrées v3) se calcule
 * depuis K_sig.
 */
class Chaine(
    val nom: String,
    /** Identifiant public (celui que le relais connaît). Sert aussi de clé dans la liste. */
    val id: String,
    val kSig: ByteArray,
    val kEnc: ByteArray,
    val cleBle: ByteArray,
    val relais: String,
    /**
     * Le téléphone a annoncé les commandes chiffrées (cmdv ≥ 3) dans un état frais et
     * authentifié : on ne lui envoie plus que du v3. Jamais remis à faux.
     */
    val cmdv3: Boolean = false,
) {
    /** K_cmd = HKDF-Expand(K_sig, "sonne-cmd-v3", 32), pour chiffrer les commandes v3. */
    val kCmd: ByteArray by lazy { Crypto.cleCommande(kSig) }

    /** Format des commandes pour ce téléphone : v3 s'il l'a annoncé, ou toujours après la coupure. */
    val envoieV3: Boolean get() = cmdv3 || !Crypto.ACCEPTER_V2

    fun renommee(nouveau: String) = Chaine(nouveau, id, kSig, kEnc, cleBle, relais, cmdv3)

    fun avecCmdV3() = Chaine(nom, id, kSig, kEnc, cleBle, relais, cmdv3 = true)

    companion object {
        fun depuisSecret(nom: String, s: ByteArray, relais: String) =
            Chaine(nom, Crypto.deriveId(s), Crypto.cleSignature(s), Crypto.cleChiffrement(s), Crypto.cleBle(s), relais)
    }
}

/** Résultat de l'analyse d'un lien collé, d'un QR code ou de 12 mots. relais null : 12 mots. */
class Saisie(val s: ByteArray, val relais: String?) {
    val id: String get() = Crypto.deriveId(s)

    companion object {
        /**
         * Lien (avec le fragment #s=…) ou 12 mots. Lève IllegalArgumentException si le texte
         * n'est ni l'un ni l'autre, ou si le lien vise un relais refusé (http:// en release).
         * Sans dépendance Android : testée sur la JVM.
         */
        fun analyser(texte: String, liste: List<String>, autoriserHttp: Boolean): Saisie {
            val t = texte.trim()
            if (t.contains('#')) {
                val s = Crypto.depuisLien(t)
                val base = t.substringBefore('#').trim().trimEnd('/')
                require(Reglages.relaisValide(base, autoriserHttp)) { "Relay refused: https:// expected" }
                return Saisie(s, base)
            }
            return Saisie(Crypto.depuisMots(t, liste), null)
        }
    }
}

/**
 * Liste « Mes autres téléphones » : un seul bloc JSON chiffré en AES-GCM par une clé de
 * l'Android Keystore (non exportable, utilisable seulement téléphone déverrouillé), rangé dans
 * les préférences en stockage par identifiant (CE). Contrairement au secret local, rien
 * n'oblige à la lire avant le premier déverrouillage : le chercheur ne tourne qu'écran
 * déverrouillé, au premier plan. Un bloc illisible n'est jamais écrasé en silence : la liste
 * le signale et ne peut être que vidée sur demande.
 */
object Chaines {

    /** Liste illisible (clé du Keystore perdue, bloc abîmé, téléphone verrouillé). */
    class Illisible(cause: Throwable) : Exception("list unreadable", cause)

    enum class Ajout { AJOUTEE, DEJA_LA, CE_TELEPHONE }

    private const val TAG = "SonneChaines"
    private const val CHAMP_CMDV = "cmdv"
    private const val PREFS = "sonne-chaines-ce"
    private const val ALIAS = "sonne-chaines-ce"

    // Ancien emplacement (1.1 en développement) : stockage par appareil, clé sans déverrouillage.
    private const val ANCIEN_PREFS = "sonne-chaines"
    private const val ANCIEN_ALIAS = "sonne-chaines"

    /** Change à chaque modification de la liste (l'interface s'y abonne). */
    val version = MutableStateFlow(0)

    @Volatile
    private var cache: List<Chaine>? = null

    /** Préférences en stockage par identifiant (illisibles avant le premier déverrouillage). */
    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La liste, dans l'ordre d'ajout. Lève Illisible si le bloc ne se déchiffre plus. */
    @Synchronized
    fun liste(context: Context): List<Chaine> {
        cache?.let { return it }
        migrerSiBesoin(context)
        val boite = prefs(context).getString("liste", null)
        val resultat = if (boite == null) {
            emptyList()
        } else {
            try {
                lire(String(dechiffrer(ALIAS, Base64.decode(boite, Base64.NO_WRAP)), Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Other phones list unreadable: ${e.javaClass.simpleName}")
                throw Illisible(e)
            }
        }
        cache = resultat
        return resultat
    }

    /** Oublie la liste gardée en mémoire (écran Nearby ou Mes autres téléphones fermé). */
    fun oublierCache() {
        cache = null
    }

    /** Ajoute une chaîne. Refuse celle de ce téléphone et les doublons (même id). */
    @Synchronized
    fun ajouter(context: Context, saisie: Saisie, nom: String, relaisParDefaut: String): Ajout {
        val actuelle = liste(context)
        val id = saisie.id
        // Le secret local peut être illisible pour l'instant : on n'empêche pas l'ajout pour autant.
        val local = try {
            val s = Coffre.secret(context)
            Crypto.deriveId(s).also { s.fill(0) }
        } catch (_: Exception) {
            null
        }
        if (id == local) return Ajout.CE_TELEPHONE
        if (actuelle.any { it.id == id }) return Ajout.DEJA_LA
        val propre = nom.trim().ifEmpty { "Phone ${actuelle.size + 1}" }.take(60)
        ecrire(context, actuelle + Chaine.depuisSecret(propre, saisie.s, saisie.relais ?: relaisParDefaut))
        return Ajout.AJOUTEE
    }

    @Synchronized
    fun renommer(context: Context, id: String, nom: String) {
        val propre = nom.trim().take(60)
        if (propre.isEmpty()) return
        ecrire(context, liste(context).map { if (it.id == id) it.renommee(propre) else it })
    }

    /**
     * Retient que ce téléphone accepte les commandes chiffrées (état frais et authentifié avec
     * cmdv ≥ 3). Sans effet s'il est déjà marqué ou absent de la liste. Jamais défait.
     */
    @Synchronized
    fun marquerCmdV3(context: Context, id: String) {
        val actuelle = liste(context)
        if (actuelle.none { it.id == id && !it.cmdv3 }) return
        ecrire(context, actuelle.map { if (it.id == id) it.avecCmdV3() else it })
    }

    @Synchronized
    fun supprimer(context: Context, id: String) {
        ecrire(context, liste(context).filter { it.id != id })
    }

    /** Vide la liste, y compris un bloc illisible (seulement à la demande de l'utilisateur). */
    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun effacerTout(context: Context) {
        prefs(context).edit().remove("liste").commit()
        try {
            Stockage.prefs(context, ANCIEN_PREFS).edit().remove("liste").commit()
        } catch (_: Exception) {
        }
        cache = emptyList()
        version.value = version.value + 1
    }

    /**
     * Format : [{"nom","id","ksig","kenc","b","relais","cmdv"}], "cmdv" (3) seulement quand le
     * téléphone a annoncé les commandes chiffrées. Une entrée de l'ancien format (S complet sous
     * "s") est relue en dérivant les clés ; elle disparaît à la prochaine écriture.
     */
    private fun lire(json: String): List<Chaine> {
        val tableau = JSONArray(json)
        return (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.optJSONObject(i) ?: return@mapNotNull null
            val nom = o.optString("nom", "?")
            val relais = o.optString("relais", Reglages.RELAIS_PAR_DEFAUT)
            try {
                if (o.has("s")) {
                    val s = Crypto.depuisHex(o.getString("s"))
                    if (s.size != Crypto.TAILLE_SECRET) return@mapNotNull null
                    Chaine.depuisSecret(nom, s, relais).also { s.fill(0) }
                } else {
                    val kSig = Crypto.depuisHex(o.getString("ksig"))
                    val kEnc = Crypto.depuisHex(o.getString("kenc"))
                    val b = Crypto.depuisHex(o.getString("b"))
                    val id = o.getString("id")
                    if (kSig.size != 32 || kEnc.size != 32 || b.size != 32 || id.length != 32) return@mapNotNull null
                    Chaine(nom, id, kSig, kEnc, b, relais, cmdv3 = o.optInt(CHAMP_CMDV, 0) >= Crypto.VERSION_COMMANDE)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    @SuppressLint("ApplySharedPref") // écriture synchrone voulue, comme pour le secret local
    private fun ecrire(context: Context, chaines: List<Chaine>) {
        val tableau = JSONArray()
        for (c in chaines) {
            tableau.put(
                JSONObject()
                    .put("nom", c.nom)
                    .put("id", c.id)
                    .put("ksig", Crypto.hex(c.kSig))
                    .put("kenc", Crypto.hex(c.kEnc))
                    .put("b", Crypto.hex(c.cleBle))
                    .put("relais", c.relais)
                    .apply { if (c.cmdv3) put(CHAMP_CMDV, Crypto.VERSION_COMMANDE) },
            )
        }
        val boite = chiffrer(tableau.toString().toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString("liste", Base64.encodeToString(boite, Base64.NO_WRAP)).commit()
        cache = chaines
        version.value = version.value + 1
    }

    /**
     * Déplace une fois la liste de l'ancien emplacement (stockage par appareil, ancienne clé)
     * vers le nouveau. Un ancien bloc illisible est laissé en place : la liste le signale.
     */
    @SuppressLint("ApplySharedPref")
    private fun migrerSiBesoin(context: Context) {
        if (prefs(context).contains("liste")) return
        val anciennes = try {
            Stockage.prefs(context, ANCIEN_PREFS)
        } catch (_: Exception) {
            return
        }
        val boite = anciennes.getString("liste", null) ?: return
        val liste = try {
            lire(String(dechiffrer(ANCIEN_ALIAS, Base64.decode(boite, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Old list unreadable: ${e.javaClass.simpleName}")
            throw Illisible(e)
        }
        ecrire(context, liste)
        anciennes.edit().remove("liste").commit()
        try {
            keystore().deleteEntry(ANCIEN_ALIAS)
        } catch (_: Exception) {
        }
        Log.i(TAG, "List moved to credential-protected storage")
    }

    // -----------------------------------------------------------------
    // Clé du Keystore (comme Coffre, alias distinct, déverrouillage exigé)
    // -----------------------------------------------------------------

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun cleOuNouvelle(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generer(deverrouillage: Boolean): SecretKey {
            val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generateur.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Inutilisable téléphone verrouillé : rien ne sert de lire la liste à ce moment-là.
                    .setUnlockedDeviceRequired(deverrouillage)
                    .build(),
            )
            return generateur.generateKey()
        }
        return try {
            generer(true)
        } catch (e: Exception) {
            Log.w(TAG, "Unlock-bound key refused (${e.javaClass.simpleName}): plain key")
            generer(false)
        }
    }

    private fun chiffrer(clair: ByteArray): ByteArray {
        fun essai(): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, cleOuNouvelle()) // le Keystore impose son propre IV aléatoire
            return c.iv + c.doFinal(clair)
        }
        return try {
            essai()
        } catch (e: KeyPermanentlyInvalidatedException) {
            keystore().deleteEntry(ALIAS)
            essai()
        }
    }

    private fun dechiffrer(alias: String, boite: ByteArray): ByteArray {
        require(boite.size > 12) { "block too short" }
        val cle = keystore().getKey(alias, null) as? SecretKey ?: error("Keystore key missing")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(128, boite.copyOfRange(0, 12)))
        return c.doFinal(boite.copyOfRange(12, boite.size))
    }
}
