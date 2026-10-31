package dev.sidali.sonne

import java.util.UUID
import kotlin.math.exp

/**
 * Proximité Bluetooth (v1.1, section « Bluetooth proximity » du protocole) : constantes et
 * logique pure, sans dépendance Android, testées sur la JVM. La radio elle-même est dans
 * Balise.kt (émission), ServeurGatt.kt (commandes reçues) et Chercheur.kt (scan, envoi).
 */
object Proximite {

    /** UUID de service Sonne : données de service de la balise et service primaire GATT. */
    val UUID_SERVICE: UUID = UUID.fromString("2af8c8b3-3a13-489b-a3a3-ac220005799b")

    /** Caractéristique Commande : écriture avec réponse d'une commande, chiffrée (v3) ou signée (v2), JSON UTF-8. */
    val UUID_COMMANDE: UUID = UUID.fromString("2af8c8b4-3a13-489b-a3a3-ac220005799b")

    /** Caractéristique État : notification de l'état chiffré. */
    val UUID_ETAT: UUID = UUID.fromString("2af8c8b5-3a13-489b-a3a3-ac220005799b")

    /** Descripteur standard d'abonnement aux notifications (CCCD). */
    val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Octet de version placé après id_ble dans les données de service. */
    const val VERSION_BALISE: Byte = 0x01

    /** Données de service : id_ble (8 octets) puis l'octet de version. */
    const val TAILLE_DONNEES = Crypto.TAILLE_ID_BLE + 1

    /** MTU demandé par le chercheur, et MTU minimal pour un message de 512 octets (+ 3 d'en-tête ATT). */
    const val MTU_DEMANDE = 517
    const val MTU_MIN = 515

    /** Taille maximale d'un message (taille maximale d'un attribut). */
    const val TAILLE_MAX_MESSAGE = 512

    /** Au-delà, une balise est perdue (niveau 0) et le lissage repart de zéro. */
    const val VU_RECEMMENT_MS = 10_000L

    /** Constante de temps du lissage exponentiel du signal. */
    const val TAU_MS = 2_000.0

    /** RSSI 127 : mesure indisponible, ignorée. */
    const val RSSI_INDISPONIBLE = 127

    /**
     * Seuils (dBm) des niveaux 2 à 5, pour une balise émise à -15 dBm environ (TX_POWER_LOW).
     * Estimations à recaler sur le terrain.
     */
    val SEUILS_DBM = intArrayOf(-88, -80, -72, -64)

    /** On ne redescend d'un niveau que quand le signal passe ce nombre de dB sous son seuil. */
    const val HYSTERESIS_DB = 3

    /** Intervalle entre deux bips, par niveau (0 : silence). */
    val RYTHME_BIP_MS = longArrayOf(0L, 2_000L, 1_300L, 800L, 450L, 200L)

    /** Niveau maximal de la jauge. */
    const val NIVEAU_MAX = 5

    // Codes d'état ATT (mêmes valeurs que BluetoothGatt.GATT_*), recopiés pour rester testables sur la JVM.
    const val GATT_SUCCESS = 0
    const val GATT_REQUEST_NOT_SUPPORTED = 0x06
    const val GATT_INVALID_ATTRIBUTE_LENGTH = 0x0d

    // -----------------------------------------------------------------
    // Mode recherche (téléphone protégé)
    // -----------------------------------------------------------------

    /**
     * Durée du mode recherche après une commande acceptée (ring, stop ou ping, par le relais ou
     * par Bluetooth) : balise et serveur GATT ne sont actifs que pendant ce mode.
     */
    const val DUREE_RECHERCHE_MS = 10L * 60 * 1000

    /** Sans relais depuis ce délai (téléphone hors ligne, peut-être perdu), le mode recherche s'ouvre seul. */
    const val HORS_LIGNE_AVANT_RECHERCHE_MS = 60L * 60 * 1000

    /** Le chercheur redemande le mode recherche par le relais à ce rythme, tant que l'écran Nearby est ouvert. */
    const val RELANCE_REVEIL_MS = 8L * 60 * 1000

    /**
     * Mode recherche : pendant 10 min après la dernière commande acceptée, tant que le téléphone
     * sonne, ou quand le relais est injoignable depuis une heure.
     * Instants en ms d'horloge monotone ; `horsLigneDepuis` vaut 0 quand le relais est joint.
     */
    fun modeRecherche(maintenant: Long, rechercheJusqua: Long, sonne: Boolean, horsLigneDepuis: Long): Boolean =
        sonne || maintenant < rechercheJusqua ||
            (horsLigneDepuis > 0 && maintenant - horsLigneDepuis >= HORS_LIGNE_AVANT_RECHERCHE_MS)

    // -----------------------------------------------------------------
    // Serveur GATT (téléphone protégé)
    // -----------------------------------------------------------------

    /** Connexions Sonne simultanées au plus (comptées dès le premier accès au service Sonne). */
    const val CONNEXIONS_MAX = 4

    /** Durée de vie d'une connexion sans commande valide (le chercheur boucle en moins de 3 s). */
    const val DUREE_SANS_COMMANDE_MS = 5_000L

    /** Durée de vie d'une connexion après une commande valide (le temps de l'accusé). */
    const val DUREE_APRES_COMMANDE_MS = 10_000L

    /** Requêtes ATT adressées à Sonne par connexion ; au-delà, la connexion est coupée. */
    const val BUDGET_REQUETES = 10

    /** Écritures invalides (mal formées ou mal signées) tolérées par connexion avant de la couper. */
    const val ECRITURES_INVALIDES_MAX = 2

    /** Commandes valides par minute sur le chemin Bluetooth (les suivantes sont ignorées). */
    const val COMMANDES_PAR_MINUTE = 20

    /**
     * Taille fixe du clair d'un état (octets UTF-8), bourré par un champ "pad" avant
     * chiffrement : la longueur du chiffré ne dit rien du modèle ni de la version. 320 octets
     * donnent un message de 508 octets, sous les 512 d'un attribut GATT.
     */
    const val TAILLE_CLAIR_ETAT = 320

    /** Longueur maximale du modèle dans un état (au-delà, il est tronqué). */
    const val MODELE_MAX = 64

    /**
     * Bourre un clair JSON (objet, terminé par « } ») avec un champ "pad" d'espaces pour qu'il
     * fasse exactement `taille` octets UTF-8. Rendu tel quel s'il est déjà trop long.
     */
    fun bourrerEtat(clair: String, taille: Int = TAILLE_CLAIR_ETAT): String {
        val t = clair.trimEnd()
        require(t.endsWith("}")) { "JSON object expected" }
        val corps = t.dropLast(1)
        val entete = if (corps.trimEnd().endsWith("{")) "\"pad\":\"" else ",\"pad\":\""
        // Corps, en-tête du champ, espaces, puis le guillemet fermant et l'accolade finale.
        val manque = taille - corps.toByteArray(Charsets.UTF_8).size - entete.length - 2
        if (manque < 0) return clair
        return corps + entete + " ".repeat(manque) + "\"}"
    }

    /**
     * Clair d'un état, écrit à la main dans l'ordre du protocole, avant le bourrage :
     * {"ts","ringing","battery","charging","app","ack","model","cmdv"}. Le champ "cmdv":3
     * (depuis la 1.6) annonce les commandes chiffrées v3 : la page et le chercheur n'en
     * envoient qu'aux téléphones qui l'ont annoncé. Le modèle est tronqué à 64 caractères.
     */
    fun clairEtat(
        ts: Long,
        sonne: Boolean,
        batterie: Int,
        enCharge: Boolean,
        app: String,
        ack: String?,
        modele: String,
    ): String {
        val accuse = if (ack == null) "null" else texteJson(ack)
        return "{\"ts\":$ts,\"ringing\":$sonne,\"battery\":$batterie,\"charging\":$enCharge," +
            "\"app\":${texteJson(app)},\"ack\":$accuse,\"model\":${texteJson(modele.take(MODELE_MAX))}," +
            "\"${Crypto.CHAMP_CAPACITE}\":${Crypto.VERSION_COMMANDE}}"
    }

    /** Chaîne JSON entre guillemets : « " », « \ » et les caractères de contrôle échappés, rien d'autre. */
    fun texteJson(texte: String): String {
        val sb = StringBuilder(texte.length + 2).append('"')
        for (c in texte) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Message d'état : {"type":"state","nonce","enc"}, écrit à la main. Le nonce (hex) et enc
     * (base64) n'ont rien à échapper en JSON, alors que JSONObject échapperait chaque « / » du
     * base64 en « \/ » et ferait varier la taille au-delà des 512 octets d'un attribut GATT.
     * Avec un clair de 320 octets, le message fait toujours 508 octets.
     */
    fun messageEtat(nonceHex: String, encBase64: String): String {
        require(nonceHex.all { it in '0'..'9' || it in 'a'..'f' }) { "hex nonce expected" }
        require(encBase64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) { "base64 attendu" }
        return "{\"type\":\"state\",\"nonce\":\"$nonceHex\",\"enc\":\"$encBase64\"}"
    }

    // -----------------------------------------------------------------
    // Chercheur
    // -----------------------------------------------------------------

    /**
     * Statuts de connexion GATT qui valent un second essai (dans le budget de 5 s) : 133
     * (GATT_ERROR, très fréquent à la première connexion), 62 (connexion non établie), 8 (délai).
     */
    fun connexionReessayable(statut: Int): Boolean = statut == 133 || statut == 62 || statut == 8

    /** Un second essai n'a de sens que si l'échec arrive vite (avant 3 s). */
    const val REESSAI_AVANT_MS = 3_000L

    /** Pause avant le second essai, le temps que la pile libère la connexion ratée. */
    const val PAUSE_REESSAI_MS = 400L

    /** Attente de l'état qui accuse la commande, une fois l'écriture acceptée. */
    const val DELAI_ACCUSE_MS = 5_000L

    /** Deux adresses vues dans ce délai pour un même id_ble : elles émettent en même temps. */
    const val SIMULTANE_MS = 2_000L

    /** Une copie d'id_ble n'est signalée que si elle dure au moins ce temps (pas à la rotation). */
    const val COPIE_APRES_MS = 3_000L

    /** Adresses essayées au plus par le chemin Bluetooth avant le relais. */
    const val ADRESSES_ESSAYEES_MAX = 3

    /** Démarrages de scan au plus sur 30 s glissantes (Android en refuse au-delà de 5, sans erreur). */
    const val DEMARRAGES_SCAN_MAX = 4
    const val FENETRE_DEMARRAGES_MS = 30_000L

    /** Données de service de la balise pour un id_ble. */
    fun donneesService(idBle: ByteArray): ByteArray {
        require(idBle.size == Crypto.TAILLE_ID_BLE) { "8-byte id_ble expected" }
        return idBle + VERSION_BALISE
    }

    /** Valeur du filtre matériel : 8 octets quelconques puis la version 1. */
    fun donneesFiltre(): ByteArray = ByteArray(TAILLE_DONNEES).also { it[TAILLE_DONNEES - 1] = VERSION_BALISE }

    /** Masque du filtre : seul l'octet de version est comparé. */
    fun masqueFiltre(): ByteArray = ByteArray(TAILLE_DONNEES).also { it[TAILLE_DONNEES - 1] = 0xFF.toByte() }

    /** id_ble lu dans des données de service, ou null si elles ne sont pas celles d'une balise Sonne v1. */
    fun lireIdBle(donnees: ByteArray?): ByteArray? {
        if (donnees == null || donnees.size < TAILLE_DONNEES) return null
        if (donnees[Crypto.TAILLE_ID_BLE] != VERSION_BALISE) return null
        return donnees.copyOf(Crypto.TAILLE_ID_BLE)
    }

    /**
     * Réponse du serveur GATT à une écriture sur la caractéristique Commande : écriture simple
     * à l'offset 0 seulement. Pendant la transition (v2 encore acceptée), de 1 à 512 octets ;
     * après la coupure, exactement 172 octets (la taille d'une commande v3).
     */
    fun statutEcriture(preparee: Boolean, offset: Int, taille: Int, accepterV2: Boolean = Crypto.ACCEPTER_V2): Int = when {
        preparee || offset != 0 -> GATT_REQUEST_NOT_SUPPORTED
        taille <= 0 || taille > TAILLE_MAX_MESSAGE -> GATT_INVALID_ATTRIBUTE_LENGTH
        !accepterV2 && taille != Crypto.TAILLE_MESSAGE_COMMANDE -> GATT_INVALID_ATTRIBUTE_LENGTH
        else -> GATT_SUCCESS
    }

    /** Niveau « brut » (sans hystérésis) pour un signal lissé, de 1 à 5. */
    fun niveauBrut(r: Double): Int = 1 + SEUILS_DBM.count { r >= it }

    /**
     * Niveau avec hystérésis : on monte dès que le seuil est atteint, on ne redescend que
     * quand le signal passe HYSTERESIS_DB sous le seuil du niveau courant.
     */
    fun niveauAvecHysteresis(r: Double, courant: Int): Int {
        val brut = niveauBrut(r)
        if (courant <= 0 || brut >= courant) return brut
        var n = courant
        while (n > brut && r < SEUILS_DBM[n - 2] - HYSTERESIS_DB) n--
        return n
    }
}

/**
 * Suivi du signal d'une balise : moyenne exponentielle pondérée par le temps (τ = 2 s),
 * niveau de 0 à 5 avec hystérésis. Les instants sont en ms d'horloge monotone (elapsedRealtime).
 */
class SuiviSignal {

    /** Signal lissé en dBm, null tant qu'aucune mesure récente. */
    var lisse: Double? = null
        private set

    /** Dernière mesure brute (dBm), 0 si aucune. */
    var derniereMesure: Int = 0
        private set

    /** Instant de la dernière mesure, -1 si jamais vue. */
    var dernierVu: Long = -1L
        private set

    private var niveau = 0

    /** Prend en compte une mesure. Une mesure de 127 (indisponible) est ignorée. */
    fun mesure(rssi: Int, instant: Long) {
        if (rssi == Proximite.RSSI_INDISPONIBLE) return
        val precedent = lisse
        if (precedent == null || dernierVu < 0 || instant - dernierVu > Proximite.VU_RECEMMENT_MS) {
            lisse = rssi.toDouble()
            niveau = 0
        } else {
            val dt = (instant - dernierVu).coerceAtLeast(0L).toDouble()
            val alpha = 1.0 - exp(-dt / Proximite.TAU_MS)
            lisse = precedent + alpha * (rssi - precedent)
        }
        derniereMesure = rssi
        dernierVu = instant
        niveau = Proximite.niveauAvecHysteresis(lisse!!, niveau)
    }

    /** true si la balise a été vue il y a moins de 10 s. */
    fun vueRecemment(instant: Long): Boolean = dernierVu >= 0 && instant - dernierVu <= Proximite.VU_RECEMMENT_MS

    /** Niveau de 0 (perdu) à 5 (très chaud). */
    fun niveau(instant: Long): Int {
        if (!vueRecemment(instant)) {
            niveau = 0
            lisse = null
            return 0
        }
        return niveau
    }
}

/**
 * Reconnaissance des balises : pour chaque chaîne connue (clé = son id), les id_ble des
 * fenêtres w - 1, w et w + 1 (horloges décalées, rotation en retard). Recalculée quand w change.
 */
class Reconnaissance(private val clesBle: Map<String, ByteArray>) {

    private var fenetre = -1L
    private var table: Map<String, String> = emptyMap()

    /** Fenêtre pour laquelle la table est calculée. */
    val fenetreCalculee: Long get() = fenetre

    /** Recalcule la table si la fenêtre a changé. */
    fun maj(w: Long) {
        if (w == fenetre) return
        val t = HashMap<String, String>()
        for ((cle, b) in clesBle) {
            for (d in -1L..1L) {
                if (w + d < 0) continue
                t[Crypto.hex(Crypto.idBle(b, w + d))] = cle
            }
        }
        table = t
        fenetre = w
    }

    /** La chaîne reconnue pour des données de service, ou null. */
    fun reconnaitre(donnees: ByteArray?): String? {
        val id = Proximite.lireIdBle(donnees) ?: return null
        return table[Crypto.hex(id)]
    }
}

/** Limite de débit sur une fenêtre glissante : au plus `maximum` événements par `dureeMs`. */
class LimiteDebit(private val maximum: Int, private val dureeMs: Long) {
    private val instants = ArrayDeque<Long>()

    @Synchronized
    fun autoriser(instant: Long): Boolean {
        while (instants.isNotEmpty() && instant - instants.first() >= dureeMs) instants.removeFirst()
        if (instants.size >= maximum) return false
        instants.addLast(instant)
        return true
    }
}

/**
 * Fraîcheur des états reçus (mêmes règles que la page web) : un état n'est retenu que si son
 * ts est plus grand que le dernier retenu et s'il a moins de 20 min.
 */
class FraicheurEtats {
    private val derniers = HashMap<String, Long>()

    @Synchronized
    fun accepter(cle: String, ts: Long, maintenant: Long): Boolean {
        if (maintenant - ts > AGE_MAX_MS) return false
        if (ts <= (derniers[cle] ?: Long.MIN_VALUE)) return false
        derniers[cle] = ts
        return true
    }

    companion object {
        const val AGE_MAX_MS = 20L * 60 * 1000
    }
}

/**
 * Signal d'une chaîne suivi adresse par adresse. id_ble est public : n'importe qui peut le
 * recopier et le réémettre. Les mesures ne sont donc jamais fusionnées entre adresses : la
 * jauge suit l'adresse la plus forte, toutes les adresses récentes restent essayables pour
 * Faire sonner, et deux adresses qui émettent en même temps pendant plus de 3 s sont signalées.
 */
class SuiviBalises {

    private val parAdresse = LinkedHashMap<String, SuiviSignal>()
    private var copieDepuis = -1L

    /** Prend en compte une mesure de l'adresse donnée. */
    fun mesure(adresse: String, rssi: Int, instant: Long) {
        parAdresse.getOrPut(adresse) { SuiviSignal() }.mesure(rssi, instant)
        // On oublie les adresses perdues : elles ne servent plus à rien.
        parAdresse.entries.removeIf { !it.value.vueRecemment(instant) }
        majCopie(instant)
    }

    /** Le suivi de l'adresse au signal lissé le plus fort parmi celles vues récemment, ou null. */
    fun meilleur(instant: Long): SuiviSignal? =
        parAdresse.values.filter { it.vueRecemment(instant) }.maxByOrNull { it.lisse ?: Double.NEGATIVE_INFINITY }

    /**
     * Adresses à essayer pour Faire sonner : celles qui émettent encore (entendues dans les
     * 2 s), la plus forte d'abord ; une adresse qui s'est tue (rotation, relance de la balise)
     * ne coûterait qu'un délai de connexion. À défaut, les adresses vues dans les 10 s, la plus
     * récente d'abord.
     */
    fun adresses(instant: Long): List<String> {
        val recentes = parAdresse.entries.filter { it.value.vueRecemment(instant) }
        val actives = recentes.filter { instant - it.value.dernierVu <= Proximite.SIMULTANE_MS }
        return if (actives.isNotEmpty()) {
            actives.sortedByDescending { it.value.lisse ?: Double.NEGATIVE_INFINITY }.map { it.key }
        } else {
            recentes.sortedByDescending { it.value.dernierVu }.map { it.key }
        }
    }

    /** true si la chaîne a été vue il y a moins de 10 s. */
    fun vueRecemment(instant: Long): Boolean = parAdresse.values.any { it.vueRecemment(instant) }

    /**
     * true si plusieurs adresses émettent le même id_ble en même temps depuis au moins 3 s :
     * quelqu'un recopie probablement la balise. Une rotation d'adresse ne déclenche rien
     * (l'ancienne adresse se tait aussitôt).
     */
    fun copieProbable(instant: Long): Boolean {
        majCopie(instant)
        return copieDepuis >= 0 && instant - copieDepuis >= Proximite.COPIE_APRES_MS
    }

    /** Suit depuis quand plusieurs adresses émettent ensemble (à chaque mesure et à chaque lecture). */
    private fun majCopie(instant: Long) {
        val simultanees = parAdresse.values.count { it.dernierVu >= 0 && instant - it.dernierVu <= Proximite.SIMULTANE_MS }
        if (simultanees < 2) {
            copieDepuis = -1L
        } else if (copieDepuis < 0) {
            copieDepuis = instant
        }
    }

    fun vider() {
        parAdresse.clear()
        copieDepuis = -1L
    }
}

/**
 * Historique des démarrages de scan : Android refuse en silence un 6e démarrage en 30 s.
 * On s'en tient à 4 sur 30 s glissantes, sans attente fixe entre deux démarrages.
 */
class HistoriqueDemarrages(
    private val maximum: Int = Proximite.DEMARRAGES_SCAN_MAX,
    private val dureeMs: Long = Proximite.FENETRE_DEMARRAGES_MS,
) {
    private val instants = ArrayDeque<Long>()

    /** Délai avant le prochain démarrage permis (0 : tout de suite). */
    @Synchronized
    fun attente(instant: Long): Long {
        while (instants.isNotEmpty() && instant - instants.first() >= dureeMs) instants.removeFirst()
        if (instants.size < maximum) return 0L
        return instants.first() + dureeMs - instant
    }

    @Synchronized
    fun noter(instant: Long) {
        instants.addLast(instant)
        while (instants.size > maximum) instants.removeFirst()
    }
}

/**
 * Connexion à évincer quand toutes les places sont prises : la plus ancienne qui n'a envoyé
 * aucune commande valide, ou null si toutes en ont envoyé une (le nouveau venu est alors refusé).
 */
fun <T> aEvincer(connexions: Collection<T>, debut: (T) -> Long, commandeValide: (T) -> Boolean): T? =
    connexions.filter { !commandeValide(it) }.minByOrNull(debut)
