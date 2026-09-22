package app.glance.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7NavigationSafetyTest {
    @Test
    fun `back action does not pop the Home root destination`() {
        assertFalse(canPopPhase7BackStack(hasPreviousDestination = false))
    }

    @Test
    fun `back action pops a non-root destination`() {
        assertTrue(canPopPhase7BackStack(hasPreviousDestination = true))
    }
}
