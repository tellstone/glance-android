package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOnboardingStateTest {
    @Test
    fun `home shows onboarding only before the first watch target is added`() {
        assertEquals(HomeContentState.ONBOARDING, homeContentState(watchTargetCount = 0))
        assertEquals(HomeContentState.DASHBOARD, homeContentState(watchTargetCount = 1))
    }

    @Test
    fun `onboarding communicates Glance privacy guarantees and its first-key action`() {
        assertEquals(
            HomeOnboardingContent(
                title = "Glance",
                tagline = "See your bitcoin. Nothing else.",
                privacyStatements = listOf(
                    "Public keys only — your private keys never touch this app",
                    "Routed through Tor by default, no accounts, no servers of ours",
                    "PIN, biometric, and duress protection built in from the start",
                ),
                primaryActionLabel = "Add your first key",
                backupPrompt = "Restoring from a backup?",
            ),
            homeOnboardingContent(),
        )
    }
}
