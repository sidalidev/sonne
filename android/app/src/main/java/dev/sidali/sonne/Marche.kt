package dev.sidali.sonne

import android.content.Context

/**
 * Interrupteur principal de Sonne, sur l'accueil. Éteint : service de veille, chien de garde,
 * balise Bluetooth, serveur GATT et connexion au relais arrêtés, notification permanente
 * retirée, et rien ne repart (démarrage du téléphone, mise à jour, ouverture de l'app) avant
 * que l'utilisateur rallume. Rallumé : tout repart comme avant, sans rien redemander.
 */
object Marche {

    /** Qui demande le démarrage de la veille. */
    enum class Source { APP, DEMARRAGE, CHIEN_DE_GARDE, REGLAGE, INTERRUPTEUR }

    /** Valeur lue dans les préférences : sans valeur enregistrée, Sonne est allumée (comme avant la 1.6.2). */
    fun lire(stocke: Boolean?): Boolean = stocke ?: true

    /** La veille peut-elle démarrer ? Éteinte, aucune source ne la relance, pas même le démarrage du téléphone. */
    fun veilleAutorisee(actif: Boolean, @Suppress("UNUSED_PARAMETER") source: Source): Boolean = actif

    /** Ce que fait un passage de l'interrupteur. */
    enum class Effet { DEMARRER_VEILLE, ARRETER_TOUT }

    fun effet(actif: Boolean): Effet = if (actif) Effet.DEMARRER_VEILLE else Effet.ARRETER_TOUT

    fun actif(context: Context): Boolean = Reglages(context).actif

    /** Bascule de l'interrupteur de l'accueil. */
    fun basculer(context: Context, actif: Boolean) {
        Reglages(context).actif = actif
        Etat.actif.value = actif
        when (effet(actif)) {
            Effet.DEMARRER_VEILLE -> VeilleService.demarrer(context, VeilleService.ACTION_RECONNECTER, Source.INTERRUPTEUR)
            Effet.ARRETER_TOUT -> {
                if (Ringer.sonne) Ringer.arreter(context)
                VeilleService.arreter(context)
            }
        }
    }
}
