package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeaderStyleTest {
    @Test
    fun `support heart is sized for clear visibility in the header`() {
        assertEquals(28, homeSupportHeartIconSize.value.toInt())
    }
}
