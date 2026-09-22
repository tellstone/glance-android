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
    fun `each donation method supplies only its own placeholder payload`() {
        assertEquals("bc1qglanceplaceholderdonation", DonationMethod.ON_CHAIN.payload)
        assertEquals("lnbc1placeholderdonation", DonationMethod.LIGHTNING.payload)
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
