package dev.sidali.sonne

/**
 * Logique pure de l'écran Autorisations : quels accès afficher, et ce que fait un tap sur une ligne.
 * Sans dépendance Android, testée par AutorisationsTest. Les intentions système sont dans Permissions.
 *
 * Règle : activer et retirer sont aussi faciles l'un que l'autre. Un accès accordé ouvre l'écran
 * exact des réglages où le retirer ; un accès refusé se demande une fois par dialogue, puis ouvre
 * l'écran des réglages (jamais de boucle de demandes).
 */
enum class Acces {
    NOTIFICATIONS,
    PLEIN_ECRAN,
    NE_PAS_DERANGER,
    BATTERIE,
    APPAREILS_PROCHES,
    CAMERA,
    INSTALLATION,
}

enum class Geste {
    /** Dialogue système (permission d'exécution, ou demande d'exclusion de l'optimisation de batterie). */
    DEMANDER,

    /** Écran des réglages où l'activer. */
    ACTIVER_DANS_REGLAGES,

    /** Écran des réglages où la retirer. */
    RETIRER_DANS_REGLAGES,
}

object Autorisations {

    /** L'accès s'accorde-t-il par un dialogue système sur cette version d'Android ? */
    fun parDialogue(acces: Acces, sdk: Int): Boolean = when (acces) {
        Acces.NOTIFICATIONS -> sdk >= 33
        Acces.APPAREILS_PROCHES, Acces.CAMERA, Acces.BATTERIE -> true
        Acces.PLEIN_ECRAN, Acces.NE_PAS_DERANGER, Acces.INSTALLATION -> false
    }

    /**
     * Ce que fait un tap sur la ligne. [dejaRefuse] : le dialogue a déjà été montré et refusé
     * pendant cette session ; on ne le remontre pas, on ouvre les réglages.
     */
    fun geste(acces: Acces, accorde: Boolean, sdk: Int, dejaRefuse: Boolean): Geste = when {
        accorde -> Geste.RETIRER_DANS_REGLAGES
        parDialogue(acces, sdk) && !dejaRefuse -> Geste.DEMANDER
        else -> Geste.ACTIVER_DANS_REGLAGES
    }

    /**
     * Ce qu'il faut pour sonner. Plein écran : réglable seulement depuis Android 14, accordé
     * d'office avant. Ne pas déranger : seulement là où Sonne peut le lever.
     */
    fun essentiels(sdk: Int, nePasDerangerUtile: Boolean): List<Acces> = listOfNotNull(
        Acces.NOTIFICATIONS,
        if (sdk >= 34) Acces.PLEIN_ECRAN else null,
        if (nePasDerangerUtile) Acces.NE_PAS_DERANGER else null,
        Acces.BATTERIE,
    )

    /** Accès liés à une option, demandés au moment où elle sert. */
    fun optionnels(bluetoothPresent: Boolean, cameraPresente: Boolean): List<Acces> = listOfNotNull(
        if (bluetoothPresent) Acces.APPAREILS_PROCHES else null,
        if (cameraPresente) Acces.CAMERA else null,
        Acces.INSTALLATION,
    )

    /** Premier lancement : l'essentiel, plus le Bluetooth si la balise est activée. */
    fun premierLancement(sdk: Int, nePasDerangerUtile: Boolean, baliseVoulue: Boolean): List<Acces> =
        essentiels(sdk, nePasDerangerUtile) + listOfNotNull(if (baliseVoulue) Acces.APPAREILS_PROCHES else null)

    /** Nombre d'accès essentiels coupés, parmi [etats] (accès vers accordé). */
    fun manquants(liste: List<Acces>, etats: Map<Acces, Boolean>): Int = liste.count { etats[it] != true }
}
