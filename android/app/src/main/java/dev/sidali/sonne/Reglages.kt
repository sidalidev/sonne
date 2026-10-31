package dev.sidali.sonne

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage chiffré par appareil (Direct Boot) : lisible dès le démarrage, avant que
 * l'utilisateur ait saisi son code. Sans lui, après un redémarrage automatique (GrapheneOS
 * au bout de 18 h verrouillé, Android au bout de 72 h), Sonne ne repartirait qu'au premier
 * déverrouillage, alors que le téléphone est justement perdu.
 *
 * Les préférences écrites par une ancienne version (stockage par identifiant, CE) sont
 * déplacées une fois, dès que le téléphone est déverrouillé.
 */
object Stockage {

    private const val MARQUES = "sonne-stockage"
    private val migres = HashSet<String>()

    /** Les SharedPreferences `nom`, en stockage chiffré par appareil. */
    fun prefs(context: Context, nom: String): SharedPreferences {
        val app = context.applicationContext ?: context
        val de = app.createDeviceProtectedStorageContext()
        migrerSiBesoin(app, de, nom)
        return de.getSharedPreferences(nom, Context.MODE_PRIVATE)
    }

    /** Dossier de cache lisible avant le premier déverrouillage. */
    fun cache(context: Context): java.io.File =
        (context.applicationContext ?: context).createDeviceProtectedStorageContext().cacheDir

    /** false tant que l'utilisateur n'a pas déverrouillé une première fois depuis le démarrage. */
    fun deverrouille(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    @Synchronized
    private fun migrerSiBesoin(app: Context, de: Context, nom: String) {
        if (nom in migres) return
        val marques = de.getSharedPreferences(MARQUES, Context.MODE_PRIVATE)
        if (marques.getBoolean(nom, false)) {
            migres += nom
            return
        }
        // Le stockage par identifiant n'est pas lisible avant le premier déverrouillage : on réessaiera.
        if (!deverrouille(app)) return
        if (de.moveSharedPreferencesFrom(app, nom)) {
            marques.edit().putBoolean(nom, true).commit()
            migres += nom
        }
    }
}

/**
 * Préférences de l'app. Le fichier est relu à chaque accès (le système le garde en cache),
 * pour suivre la migration vers le stockage par appareil.
 */
@android.annotation.SuppressLint("ApplySharedPref")
class Reglages(context: Context) {

    private val contexte = context.applicationContext ?: context
    private val prefs: SharedPreferences get() = Stockage.prefs(contexte, "sonne")

    /** Adresse du relais (https:// ; http:// accepté seulement en debug, pour un relais local). */
    var relais: String
        get() = prefs.getString("relais", RELAIS_PAR_DEFAUT) ?: RELAIS_PAR_DEFAUT
        set(v) {
            if (!relaisValide(v)) {
                Log.w("SonneReglages", "Relay address refused")
                return
            }
            prefs.edit().putString("relais", v.trim().trimEnd('/')).apply()
        }

    /**
     * Interrupteur principal. false : plus aucun travail en arrière-plan (veille, chien de garde,
     * balise, relais), et cela tient après un redémarrage jusqu'à ce que l'utilisateur rallume.
     * commit() : le récepteur du démarrage doit lire la valeur écrite, même si le processus meurt juste après.
     */
    var actif: Boolean
        get() = Marche.lire(if (prefs.contains("actif")) prefs.getBoolean("actif", true) else null)
        set(v) {
            prefs.edit().putBoolean("actif", v).commit()
        }

    /** Durée avant arrêt automatique de la sonnerie, en minutes (1 à 10). */
    var dureeMin: Int
        get() = prefs.getInt("duree_min", 3).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("duree_min", v.coerceIn(1, 10)).apply()

    /** Faire clignoter la lampe torche pendant la sonnerie. */
    var torche: Boolean
        get() = prefs.getBoolean("torche", true)
        set(v) = prefs.edit().putBoolean("torche", v).apply()

    /** Balise Bluetooth et serveur GATT (Nearby) : le téléphone peut être trouvé et sonné sans Internet. */
    var balise: Boolean
        get() = prefs.getBoolean("balise", true)
        set(v) = prefs.edit().putBoolean("balise", v).apply()

    /** Bips de l'écran Nearby quand on cherche un autre téléphone. */
    var bips: Boolean
        get() = prefs.getBoolean("bips", true)
        set(v) = prefs.edit().putBoolean("bips", v).apply()

    /** The key was saved somewhere (password manager or rescue kit). */
    var cleSauvegardee: Boolean
        get() = prefs.getBoolean("cle_sauvegardee", false)
        set(v) = prefs.edit().putBoolean("cle_sauvegardee", v).apply()

    /** The user proved they have the key by typing 3 of the 12 words. */
    var cleVerifiee: Boolean
        get() = prefs.getBoolean("cle_verifiee", false)
        set(v) = prefs.edit().putBoolean("cle_verifiee", v).apply()

    /** Mise à jour intégrée : vérifier chaque jour et à l'ouverture (activée par défaut). */
    var majAuto: Boolean
        get() = prefs.getBoolean("maj_auto", true)
        set(v) = prefs.edit().putBoolean("maj_auto", v).apply()

    /** Dernière annonce de mise à jour reçue du relais (JSON brut), null si aucune. */
    var majAnnonce: String?
        get() = prefs.getString("maj_annonce", null)
        set(v) = prefs.edit().putString("maj_annonce", v).apply()

    /** Dernière vérification réussie (ms Unix). */
    var majDerniereVerif: Long
        get() = prefs.getLong("maj_derniere_verif", 0L)
        set(v) = prefs.edit().putLong("maj_derniere_verif", v).apply()

    /** Dernier essai de vérification, réussi ou non (ms Unix). */
    var majDernierEssai: Long
        get() = prefs.getLong("maj_dernier_essai", 0L)
        set(v) = prefs.edit().putLong("maj_dernier_essai", v).apply()

    /** versionCode déjà signalé par une notification (une seule par version). */
    var majNotifiee: Long
        get() = prefs.getLong("maj_notifiee", 0L)
        set(v) = prefs.edit().putLong("maj_notifiee", v).apply()

    /** L'écran des autorisations a déjà été montré une fois. */
    var permissionsVues: Boolean
        get() = prefs.getBoolean("permissions_vues", false)
        set(v) = prefs.edit().putBoolean("permissions_vues", v).apply()

    /** L'écran Appairage a déjà été ouvert (sinon l'accueil le propose comme étape suivante). */
    var appairageVu: Boolean
        get() = prefs.getBoolean("appairage_vu", false)
        set(v) = prefs.edit().putBoolean("appairage_vu", v).apply()

    /** Dernière connexion réussie au relais (ms Unix, 0 si jamais). */
    var derniereConnexion: Long
        get() = prefs.getLong("derniere_connexion", 0L)
        set(v) = prefs.edit().putLong("derniere_connexion", v).apply()

    /**
     * Nonces des commandes acceptées encore dans la fenêtre de 15 min, avec le ts de leur
     * commande (anti rejeu, survivent à un redémarrage). Format : « nonce:ts,nonce:ts ».
     * Une entrée de l'ancien format (sans ts) est gardée une fenêtre entière.
     */
    var nonces: Map<String, Long>
        get() {
            val maintenant = System.currentTimeMillis()
            val resultat = LinkedHashMap<String, Long>()
            for (morceau in prefs.getString("nonces", "")!!.split(',')) {
                if (morceau.isEmpty()) continue
                val deux = morceau.split(':')
                resultat[deux[0]] = deux.getOrNull(1)?.toLongOrNull() ?: maintenant
            }
            return resultat
        }
        set(v) = prefs.edit().putString("nonces", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    /** ts de la dernière ring ou stop acceptée (les plus anciennes sont ignorées). */
    var dernierTsOrdre: Long
        get() = prefs.getLong("dernier_ts_ordre", 0L)
        set(v) = prefs.edit().putLong("dernier_ts_ordre", v).apply()

    /**
     * Écrit les nonces et dernierTsOrdre ensemble et de façon synchrone (commit), AVANT
     * d'exécuter la commande : si le processus meurt juste après (plantage, arrêt forcé,
     * redémarrage), une ring captée sur les ondes ne peut pas être rejouée.
     */
    fun enregistrerFiltre(nonces: Map<String, Long>, dernierTsOrdre: Long) {
        prefs.edit()
            .putString("nonces", nonces.entries.joinToString(",") { "${it.key}:${it.value}" })
            .putLong("dernier_ts_ordre", dernierTsOrdre)
            .commit()
    }

    /** Volume d'alarme à restaurer si le processus meurt pendant une sonnerie (-1 : rien). */
    var volumeARestaurer: Int
        get() = prefs.getInt("volume_a_restaurer", -1)
        set(v) = prefs.edit().putInt("volume_a_restaurer", v).commit().let { }

    /** Filtre Ne pas déranger à restaurer (-1 : rien). */
    var filtreARestaurer: Int
        get() = prefs.getInt("filtre_a_restaurer", -1)
        set(v) = prefs.edit().putInt("filtre_a_restaurer", v).commit().let { }

    companion object {
        const val RELAIS_PAR_DEFAUT = "https://sonne.sidali.dev"

        /** Sous-protocole WebSocket commun au relais (protocole v2). */
        const val SOUS_PROTOCOLE = "sonne.v2"

        /**
         * Adresse du WebSocket du téléphone : wss://<relais>/ws/phone.
         * Rien d'identifiant dans l'URL : la preuve P passe dans Sec-WebSocket-Protocol.
         */
        fun urlWebSocket(relais: String): String {
            val base = relais.trim().trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/ws/phone"
        }

        /** Valeur de l'en-tête Sec-WebSocket-Protocol du téléphone : « sonne.v2, sonne.p.<P> ». */
        fun sousProtocoles(preuve: ByteArray): String = "$SOUS_PROTOCOLE, sonne.p.${Crypto.hex(preuve)}"

        /**
         * Adresse acceptable pour un relais. http:// seulement en debug : en release le trafic
         * en clair est bloqué, la veille boucle en échec et le lien d'appairage pousserait à
         * saisir la clé sur une page non sécurisée.
         */
        fun relaisValide(texte: String, autoriserHttp: Boolean = BuildConfig.DEBUG): Boolean {
            val t = texte.trim()
            val reste = when {
                t.startsWith("https://") -> t.removePrefix("https://")
                t.startsWith("http://") && autoriserHttp -> t.removePrefix("http://")
                else -> return false
            }
            return reste.isNotEmpty() && !reste.contains(' ') && !reste.contains('#') && !reste.contains('?')
        }

        /** Nom d'hôte (avec port éventuel) d'une adresse de relais, pour l'afficher. */
        fun hote(relais: String): String =
            relais.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
    }
}

/**
 * Coffre du secret S : S est chiffré en AES-GCM par une clé de l'Android Keystore
 * (non exportable, utilisable avant le premier déverrouillage), puis rangé dans les
 * préférences en stockage par appareil.
 *
 * Règle d'or : un S illisible n'est JAMAIS remplacé en silence. Changer de S révoque tous
 * les navigateurs, au moment même où l'on cherche peut-être à faire sonner le téléphone.
 * - Pas de S enregistré : premier lancement, on en crée un.
 * - S présent mais illisible pour une raison passagère : Indisponible, on réessaiera.
 * - Échecs définitifs répétés (étiquette GCM refusée, clé invalidée ou disparue, boîte
 *   abîmée) : Perdu. L'ancienne boîte est gardée sous « s_perdu », l'accueil prévient
 *   l'utilisateur, et un nouveau S n'est créé qu'après sa confirmation (nouvelleChaine).
 */
object Coffre {

    /** Erreur passagère : réessayer plus tard, ne rien réécrire. */
    class Indisponible(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Perte établie : il faut l'accord de l'utilisateur pour créer une nouvelle clé. */
    class Perdu(message: String) : Exception(message)

    private class CleAbsente : Exception("Keystore key missing")

    private const val TAG = "SonneCoffre"
    private const val ALIAS = "sonne-coffre"
    private const val PREFS = "sonne-coffre"
    private const val ECHECS_AVANT_PERTE = 3
    private const val DUREE_MIN_AVANT_PERTE_MS = 60_000L

    @Volatile
    private var cache: ByteArray? = null

    /**
     * Le secret S courant, créé au tout premier lancement.
     * Lève Indisponible (réessayer) ou Perdu (demander à l'utilisateur).
     */
    @Synchronized
    fun secret(context: Context): ByteArray {
        cache?.let { return it.copyOf() }
        val prefs = Stockage.prefs(context, PREFS)
        val boite = prefs.getString("s", null)
        if (boite == null) {
            if (prefs.getBoolean("perdu", false)) throw Perdu("key lost, waiting for the user's consent")
            // Avant le premier déverrouillage, S peut encore dormir dans l'ancien stockage (pas encore migré).
            if (!Stockage.deverrouille(context)) throw Indisponible("storage not unlocked yet")
            val s = Crypto.genererSecret()
            ecrire(context, s)
            cache = s
            return s.copyOf()
        }
        val s = try {
            dechiffrer(boite)
        } catch (e: Exception) {
            if (estDefinitif(e)) noterEchecDefinitif(prefs, boite)
            throw Indisponible("key unreadable for now", e)
        }
        if (prefs.contains("echecs")) prefs.edit().remove("echecs").remove("premier_echec").apply()
        cache = s
        return s.copyOf()
    }

    /** true si la perte de la clé a été établie (l'accueil le signale). */
    fun estPerdu(context: Context): Boolean =
        try {
            Stockage.prefs(context, PREFS).getBoolean("perdu", false)
        } catch (_: Exception) {
            false
        }

    /** Révoque tous les contrôleurs, ou répare une clé perdue : tire un nouveau S. */
    @Synchronized
    @android.annotation.SuppressLint("ApplySharedPref")
    fun nouvelleChaine(context: Context): ByteArray {
        val s = Crypto.genererSecret()
        ecrire(context, s)
        Stockage.prefs(context, PREFS).edit().remove("perdu").remove("echecs").remove("premier_echec").commit()
        cache = s
        Reglages(context).apply { cleSauvegardee = false; cleVerifiee = false }
        Etat.secretPerdu.value = false
        Etat.versionSecret.value = Etat.versionSecret.value + 1
        return s.copyOf()
    }

    private fun estDefinitif(e: Exception): Boolean =
        e is AEADBadTagException || e is KeyPermanentlyInvalidatedException || e is CleAbsente ||
            e is IllegalStateException || e is IllegalArgumentException

    /** Compte les échecs définitifs ; au bout de 3, étalés sur au moins une minute, la clé est perdue. */
    @android.annotation.SuppressLint("ApplySharedPref")
    private fun noterEchecDefinitif(prefs: SharedPreferences, boite: String) {
        val maintenant = System.currentTimeMillis()
        val n = prefs.getInt("echecs", 0) + 1
        val premier = prefs.getLong("premier_echec", 0L).takeIf { it > 0 } ?: maintenant
        if (n >= ECHECS_AVANT_PERTE && maintenant - premier >= DUREE_MIN_AVANT_PERTE_MS) {
            Log.e(TAG, "Vault key lost after $n failures: the old box is kept under s_perdu")
            prefs.edit()
                .putString("s_perdu", boite)
                .remove("s")
                .putBoolean("perdu", true)
                .remove("echecs")
                .remove("premier_echec")
                .commit()
            Etat.secretPerdu.value = true
            throw Perdu("vault key lost")
        }
        prefs.edit().putInt("echecs", n).putLong("premier_echec", premier).commit()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** La clé existante, sans jamais en créer une (une nouvelle clé ne déchiffrerait rien). */
    private fun cleExistante(): SecretKey = keystore().getKey(ALIAS, null) as? SecretKey ?: throw CleAbsente()

    /** La clé, créée si elle n'existe pas (seulement pour écrire un nouveau S). */
    private fun cleOuNouvelle(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generateur.init(
            // Pas de setUnlockedDeviceRequired : la clé doit servir avant le premier déverrouillage.
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generateur.generateKey()
    }

    private fun dechiffrer(boite: String): ByteArray {
        val octets = Base64.decode(boite, Base64.NO_WRAP)
        require(octets.size > 12) { "box too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, cleExistante(), GCMParameterSpec(128, octets.copyOfRange(0, 12)))
        val s = c.doFinal(octets.copyOfRange(12, octets.size))
        check(s.size == Crypto.TAILLE_SECRET) { "key of the wrong size" }
        return s
    }

    @android.annotation.SuppressLint("ApplySharedPref") // écriture synchrone voulue : le secret doit être sur disque
    private fun ecrire(context: Context, s: ByteArray) {
        val boite = try {
            chiffrer(s)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Clé invalidée : on la remplace (elle ne déchiffre plus rien de toute façon).
            keystore().deleteEntry(ALIAS)
            chiffrer(s)
        }
        Stockage.prefs(context, PREFS).edit()
            .putString("s", Base64.encodeToString(boite, Base64.NO_WRAP))
            .commit()
    }

    private fun chiffrer(s: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, cleOuNouvelle()) // le Keystore impose son propre IV aléatoire
        return c.iv + c.doFinal(s)
    }
}

/** État partagé entre le service, la sonnerie et l'interface. */
object Etat {
    /** Interrupteur principal (voir Reglages.actif), relu à chaque retour au premier plan. */
    val actif = MutableStateFlow(true)
    val connecte = MutableStateFlow(false)
    val derniereConnexion = MutableStateFlow(0L)
    val sonne = MutableStateFlow(false)
    val versionSecret = MutableStateFlow(0)
    val secretPerdu = MutableStateFlow(false)

    /** La balise Bluetooth (Nearby) est en cours d'émission (confirmé par la pile Bluetooth). */
    val balise = MutableStateFlow(false)

    /** Mode recherche ouvert : balise et serveur GATT voulus (voir Proximite.modeRecherche). */
    val modeRecherche = MutableStateFlow(false)

    /** L'activité principale est au premier plan (le scan de Nearby ne tourne que dans ce cas). */
    val premierPlan = MutableStateFlow(false)

    /** Mise à jour intégrée (bandeau de l'accueil). */
    val miseAJour = MutableStateFlow<EtatMaj>(EtatMaj.Aucune)

    /** Écran système de confirmation d'installation, lancé par l'activité dès qu'elle est au premier plan. */
    val confirmationInstallation = MutableStateFlow<android.content.Intent?>(null)
}

/** Liste BIP39 anglaise officielle, embarquée dans res/raw. */
object Bip39 {
    @Volatile
    private var liste: List<String>? = null

    fun liste(context: Context): List<String> = liste ?: synchronized(this) {
        liste ?: context.resources.openRawResource(R.raw.bip39_english).bufferedReader().use { r ->
            r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.also { liste = it }
    }
}
