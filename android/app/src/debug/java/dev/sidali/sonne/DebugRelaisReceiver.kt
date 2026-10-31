package dev.sidali.sonne

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Version debug seulement : réglages posés par adb, pour les tests de bout en bout.
 *
 * Adresse du relais :
 *   adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es relay http://10.0.2.2:7700
 * Ajout d'un téléphone à « Mes autres téléphones » (lien d'appairage, lu dans le journal
 * SONNE_PAIR de l'autre appareil ; guillemets simples pour que le shell garde le #) :
 *   adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es pair "'http://10.0.2.2:7700/#s=…'" --es nom B
 * Envoi d'une commande à un de ces téléphones par le relais, comme l'écran Nearby (même
 * EnvoiCommande : format v2 ou v3 selon la capacité connue, renvoi en v3 sur un 400 ; --ez auto
 * true pour une vérification automatique, précédée de la présence) :
 *   adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es envoyer ring --es vers 84c083
 *
 * Déclaré dans src/debug/AndroidManifest.xml avec la permission DUMP : seul le shell adb peut
 * l'appeler, pas une autre app installée (qui pourrait sinon rediriger le téléphone vers son
 * relais, et donc vers sa page, qui recevrait la clé à l'appairage).
 * L'app doit avoir été lancée une fois (une app jamais ouverte ne reçoit pas de broadcast).
 */
class DebugRelaisReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("pair")?.let { ajouterChaine(context, it, intent.getStringExtra("nom").orEmpty()) }
        intent.getStringExtra("envoyer")?.let {
            envoyer(context, it, intent.getStringExtra("vers").orEmpty(), intent.getBooleanExtra("auto", false))
        }
        val relais = intent.getStringExtra("relay") ?: return
        if (!Reglages.relaisValide(relais)) {
            Log.w(TAG, "Relay address refused: $relais")
            return
        }
        Reglages(context).relais = relais
        Log.i(TAG, "Relay set by adb: $relais")
        // Service déjà lancé : appel direct (un démarrage depuis un broadcast peut être refusé).
        VeilleService.instance?.reconnecter() ?: VeilleService.demarrer(context, VeilleService.ACTION_RECONNECTER)
    }

    /** Même chemin que le lien collé dans « Mes autres téléphones » (sans la confirmation). */
    private fun ajouterChaine(context: Context, lien: String, nom: String) {
        val saisie = try {
            Saisie.analyser(lien, Bip39.liste(context), autoriserHttp = true)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Link refused: ${e.message}")
            return
        }
        val resultat = try {
            Chaines.ajouter(context, saisie, nom, Reglages(context).relais)
        } catch (e: Chaines.Illisible) {
            Log.w(TAG, "Other phones list unreadable")
            return
        }
        Log.i(TAG, "Other phone added by adb: $resultat (id ${saisie.id.take(6)})")
    }

    /** Envoie une commande par le relais à la chaîne dont l'id commence par `vers`. */
    private fun envoyer(context: Context, cmd: String, vers: String, automatique: Boolean) {
        if (cmd !in Crypto.COMMANDES || vers.isEmpty()) return
        val chaine = try {
            Chaines.liste(context).firstOrNull { it.id.startsWith(vers) }
        } catch (e: Chaines.Illisible) {
            null
        }
        if (chaine == null) {
            Log.w(TAG, "No other phone with id $vers")
            return
        }
        val attente = goAsync()
        EnvoiCommande(context, chaine, cmd, FraicheurEtats(), automatique) { r ->
            Log.i(TAG, "Sent by adb: $cmd to ${chaine.id.take(6)} (encrypted known: ${chaine.cmdv3}): $r")
            attente.finish()
        }.lancer(emptyList())
    }

    private companion object {
        const val TAG = "SONNE_DEBUG"
    }
}
