package dev.sidali.sonne

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf


/** Activité principale : Accueil, Appairage, Réglages, Nearby, Mes autres téléphones et les autorisations. */
class MainActivity : ComponentActivity() {

    /** Incrémenté à chaque retour au premier plan, pour relire l'état des autorisations. */
    private val reprise = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Canaux.creer(this)
        Etat.actif.value = Marche.actif(this)
        VeilleService.demarrer(this)
        MiseAJour.initialiser(this)
        MiseAJour.verifierOuverture(this)
        if (savedInstanceState == null) traiterIntention(intent)
        setContent {
            ThemeSonne {
                AppSonne(reprise = reprise.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        traiterIntention(intent)
    }

    /** Tap sur la notification de mise à jour : téléchargement, vérifications, installation. */
    private fun traiterIntention(intent: Intent?) {
        if (intent?.action == MiseAJour.ACTION_INSTALLER) MiseAJour.lancer(this)
    }

    override fun onResume() {
        super.onResume()
        Etat.premierPlan.value = true
        Etat.actif.value = Marche.actif(this)
        Etat.secretPerdu.value = Coffre.estPerdu(this)
        reprise.intValue++
    }

    override fun onPause() {
        // Le scan de l'écran Nearby s'arrête dès que l'app n'est plus au premier plan.
        Etat.premierPlan.value = false
        super.onPause()
    }
}
