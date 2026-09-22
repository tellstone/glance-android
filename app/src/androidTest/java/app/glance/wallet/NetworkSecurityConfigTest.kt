package app.glance.wallet

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24)
class NetworkSecurityConfigTest {
    @Test
    fun onlyBundledMempoolOnionHostPermitsCleartext() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertTrue(policy.isCleartextTrafficPermitted("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"))
        assertFalse(policy.isCleartextTrafficPermitted("mempool.space"))
    }
}
