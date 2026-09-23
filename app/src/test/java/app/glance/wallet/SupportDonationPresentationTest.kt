package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportDonationPresentationTest {
    @Test
    fun `on-chain is the default donation method`() {
        assertEquals(DonationMethod.ON_CHAIN, DonationMethod.default)
        assertEquals("On-chain", DonationMethod.ON_CHAIN.label)
    }

    @Test
    fun `each donation method supplies its configured public payload`() {
        assertEquals(BuildConfig.DONATION_ON_CHAIN, DonationMethod.ON_CHAIN.payload)
        assertEquals(BuildConfig.DONATION_LIGHTNING, DonationMethod.LIGHTNING.payload)
    }

    @Test
    fun `support and receive QR codes use the shared rounded warm white card treatment`() {
        assertEquals(walletDetailTabShape, supportDonationToggleShape)
        assertEquals(112, supportDonationHeartSize.value.toInt())
        assertEquals(GlanceCardShape, qrCardShape)
        assertEquals(10, qrCardInset.value.toInt())
        assertEquals(GlanceText, qrBackgroundColor)
        assertEquals(0xFF000000.toInt(), qrModuleColor)
    }
}
