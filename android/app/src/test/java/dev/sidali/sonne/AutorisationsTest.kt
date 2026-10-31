package dev.sidali.sonne

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutorisationsTest {

    @Test
    fun accordeOuvreToujoursLesReglagesPourRetirer() {
        for (acces in Acces.values()) for (sdk in listOf(29, 33, 34, 36)) for (refuse in listOf(false, true)) {
            assertEquals(Geste.RETIRER_DANS_REGLAGES, Autorisations.geste(acces, accorde = true, sdk = sdk, dejaRefuse = refuse))
        }
    }

    @Test
    fun refuseSeDemandeUneSeuleFoisPuisOuvreLesReglages() {
        assertEquals(Geste.DEMANDER, Autorisations.geste(Acces.CAMERA, false, 34, dejaRefuse = false))
        assertEquals(Geste.ACTIVER_DANS_REGLAGES, Autorisations.geste(Acces.CAMERA, false, 34, dejaRefuse = true))
        assertEquals(Geste.DEMANDER, Autorisations.geste(Acces.APPAREILS_PROCHES, false, 34, dejaRefuse = false))
        assertEquals(Geste.ACTIVER_DANS_REGLAGES, Autorisations.geste(Acces.APPAREILS_PROCHES, false, 34, dejaRefuse = true))
    }

    @Test
    fun notificationsParDialogueDepuisAndroid13Seulement() {
        assertEquals(Geste.ACTIVER_DANS_REGLAGES, Autorisations.geste(Acces.NOTIFICATIONS, false, 32, false))
        assertEquals(Geste.DEMANDER, Autorisations.geste(Acces.NOTIFICATIONS, false, 33, false))
    }

    @Test
    fun accesSpeciauxSansDialogue() {
        for (acces in listOf(Acces.PLEIN_ECRAN, Acces.NE_PAS_DERANGER, Acces.INSTALLATION)) {
            assertFalse(Autorisations.parDialogue(acces, 36))
            assertEquals(Geste.ACTIVER_DANS_REGLAGES, Autorisations.geste(acces, false, 36, false))
        }
        assertTrue(Autorisations.parDialogue(Acces.BATTERIE, 29))
    }

    @Test
    fun essentielsSelonLAppareil() {
        assertEquals(
            listOf(Acces.NOTIFICATIONS, Acces.BATTERIE),
            Autorisations.essentiels(sdk = 33, nePasDerangerUtile = false),
        )
        assertEquals(
            listOf(Acces.NOTIFICATIONS, Acces.PLEIN_ECRAN, Acces.NE_PAS_DERANGER, Acces.BATTERIE),
            Autorisations.essentiels(sdk = 34, nePasDerangerUtile = true),
        )
    }

    @Test
    fun optionnelsSelonLeMateriel() {
        assertEquals(
            listOf(Acces.APPAREILS_PROCHES, Acces.CAMERA, Acces.INSTALLATION),
            Autorisations.optionnels(bluetoothPresent = true, cameraPresente = true),
        )
        assertEquals(listOf(Acces.INSTALLATION), Autorisations.optionnels(bluetoothPresent = false, cameraPresente = false))
    }

    @Test
    fun premierLancementAjouteLeBluetoothSiLaBaliseEstVoulue() {
        assertFalse(Acces.APPAREILS_PROCHES in Autorisations.premierLancement(34, false, baliseVoulue = false))
        assertTrue(Acces.APPAREILS_PROCHES in Autorisations.premierLancement(34, false, baliseVoulue = true))
        assertFalse(Acces.CAMERA in Autorisations.premierLancement(34, true, baliseVoulue = true))
    }

    @Test
    fun manquantsCompteLesAccesCoupesOuInconnus() {
        val liste = listOf(Acces.NOTIFICATIONS, Acces.BATTERIE, Acces.PLEIN_ECRAN)
        assertEquals(0, Autorisations.manquants(liste, liste.associateWith { true }))
        assertEquals(2, Autorisations.manquants(liste, mapOf(Acces.NOTIFICATIONS to true, Acces.BATTERIE to false)))
    }
}
