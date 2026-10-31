package dev.sidali.sonne

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarcheTest {

    @Test
    fun allumeeParDefautCommeAvant() {
        assertTrue(Marche.lire(null))
        assertTrue(Marche.lire(true))
        assertFalse(Marche.lire(false))
    }

    @Test
    fun eteinteRienNeRelanceLaVeille() {
        for (source in Marche.Source.values()) {
            assertFalse(source.name, Marche.veilleAutorisee(false, source))
            assertTrue(source.name, Marche.veilleAutorisee(true, source))
        }
    }

    @Test
    fun memeGesteDansLesDeuxSens() {
        assertEquals(Marche.Effet.ARRETER_TOUT, Marche.effet(false))
        assertEquals(Marche.Effet.DEMARRER_VEILLE, Marche.effet(true))
    }
}
