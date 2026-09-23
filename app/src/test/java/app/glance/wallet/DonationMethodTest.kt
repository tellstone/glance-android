package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DonationMethodTest {
    @Test
    fun `donation methods use build supplied public values`() {
        assertEquals(BuildConfig.DONATION_ON_CHAIN, DonationMethod.ON_CHAIN.payload)
        assertEquals(BuildConfig.DONATION_LIGHTNING, DonationMethod.LIGHTNING.payload)
    }

    @Test
    fun `development Lightning donation fallback matches the short address shape`() {
        assertEquals("glance@wallet.cash", BuildConfig.DONATION_LIGHTNING)
    }
}
