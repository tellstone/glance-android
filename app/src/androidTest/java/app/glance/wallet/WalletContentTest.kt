package app.glance.wallet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.AddressHistoryEntity
import app.glance.wallet.core.data.db.BlockTimestampCacheEntity
import app.glance.wallet.core.data.db.DerivedAddressEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.LabelEntity
import app.glance.wallet.core.data.db.LabelReferenceType
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.UtxoEntity
import app.glance.wallet.core.data.db.WatchedKeyEntity
import app.glance.wallet.core.data.db.WatchTargetType
import app.glance.wallet.core.data.db.WalletGroupEntity
import app.glance.wallet.core.security.AuthenticationCoordinator
import app.glance.wallet.core.security.ExplorerPreset
import app.glance.wallet.core.security.ProfileDatabaseManager
import app.glance.wallet.core.security.SecurityPreferences
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.UtxoView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WalletContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = SecurityPreferencesStore.forTesting(context, "wallet-content-ui")
    private val profiles = ProfileDatabaseManager(context)

    @After
    fun tearDown() {
        runBlocking { preferences.wipe() }
        profiles.deleteAll()
    }

    @Test
    fun addWatchTargetScreenMakesSingleAddressesFirstClass() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        composeRule.setContent {
            GlanceTheme {
                AddWatchTargetScreen(
                    database = database,
                    discoverXpub = { error("Format discovery should not run") },
                    torReady = true,
                    onImported = {},
                    onBack = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Add watch target").assertCountEquals(2)
        composeRule.onNodeWithText("Bitcoin address, public key, or descriptor").assertIsDisplayed()
        composeRule.onNodeWithText("Paste or scan a mainnet Bitcoin address, extended public key, or supported descriptor.")
            .assertIsDisplayed()
        database.close()
    }

    @Test
    fun torBootstrapShowsTheTorConnectionStatusBelowTheLoadingBar() {
        composeRule.setContent { GlanceTheme { TorBootstrapScreen() } }

        composeRule.onNodeWithTag("tor_bootstrap_logo").assertIsDisplayed()
        composeRule.onNodeWithText("Glance").assertIsDisplayed()
        composeRule.onNodeWithTag("tor_bootstrap_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Connecting to Tor").assertIsDisplayed()
    }

    @Test
    fun phase7SettingsRestoreSecurityControlsAndPersistChartChoice() {
        val authentication = AuthenticationCoordinator(preferences, profiles)
        composeRule.setContent {
            GlanceTheme {
                Phase7SettingsContent(
                    settings = SecurityPreferences(),
                    preferences = preferences,
                    authentication = authentication,
                    tor = TorController(context),
                    biometricAvailable = false,
                    onBack = {},
                    onSupport = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("Device biometrics are not available or enrolled.").assertIsDisplayed()
        composeRule.onNodeWithTag("setting_biometric_unlock").assertIsNotEnabled()
        composeRule.onNodeWithTag("setting_show_balance_chart").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { !preferences.data.first().showBalanceChart }
        }
        composeRule.onNodeWithText("Duress PIN").performClick()
        composeRule.onNodeWithText("Not set").assertIsDisplayed()
        composeRule.onNodeWithTag("phase7_settings_scroll")
            .performScrollToNode(hasText("Set up duress profile"))
        composeRule.onNodeWithText("Set up duress profile").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pin_keypad").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("pin_keypad").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("phase7_settings_scroll")
            .performScrollToNode(hasText("Erase all data"))
        composeRule.onNodeWithText("Erase all data").assertIsDisplayed()
        composeRule.onNodeWithText("Erase all data").performClick()
        composeRule.onNodeWithText("Erase all data?").assertIsDisplayed()
        composeRule.onNodeWithText("This permanently deletes both encrypted wallet profiles, security settings, local cached data, and cached Tor state. This cannot be undone.").assertIsDisplayed()
    }

    @Test
    fun settingsNoLongerExposesTheGlobalUtxoViewChoice() {
        val authentication = AuthenticationCoordinator(preferences, profiles)
        composeRule.setContent {
            GlanceTheme {
                Phase7SettingsContent(
                    settings = SecurityPreferences(),
                    preferences = preferences,
                    authentication = authentication,
                    tor = TorController(context),
                    biometricAvailable = false,
                    onBack = {},
                    onSupport = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXO view").assertDoesNotExist()
        composeRule.onNodeWithTag("setting_utxo_view").assertDoesNotExist()
    }

    @Test
    fun settingsUseGroupedMockStyleWhileKeepingOnlyImplementedRows() {
        val authentication = AuthenticationCoordinator(preferences, profiles)
        composeRule.setContent {
            GlanceTheme {
                Phase7SettingsContent(
                    settings = SecurityPreferences(),
                    preferences = preferences,
                    authentication = authentication,
                    tor = TorController(context),
                    biometricAvailable = false,
                    onBack = {},
                    onSupport = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_group_wallet").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_external").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_app_behavior").assertIsDisplayed()
        composeRule.onNodeWithText("PIN code").assertDoesNotExist()
        composeRule.onNodeWithText("Street mode").assertDoesNotExist()
        composeRule.onNodeWithText("Export backup").assertDoesNotExist()
        composeRule.onNodeWithText("Share error log").assertDoesNotExist()
        composeRule.onNodeWithText("Duress PIN").performClick()
        composeRule.onNodeWithTag("phase7_settings_scroll")
            .performScrollToNode(hasText("Set up duress profile"))
        composeRule.onNodeWithText("Set up duress profile").assertIsDisplayed()
    }

    @Test
    fun connectedTorStatusIsVisibleAndAccessible() {
        composeRule.setContent { GlanceTheme { TorStatusRow(TorState.Ready(app.glance.wallet.core.network.NetworkRoute.Socks(java.net.InetSocketAddress("127.0.0.1", 19050))), {}) } }

        composeRule.onNodeWithTag("tor_status_connected").assertIsDisplayed()
        composeRule.onNodeWithText("Tor connected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tor connected").assertIsDisplayed()
    }

    @Test
    fun walletSyncStatusIsSilentBecausePullToRefreshShowsProgress() {
        composeRule.setContent { GlanceTheme { WalletSyncStatus(WalletSyncState.Syncing, {}) } }

        composeRule.onNodeWithTag("wallet_syncing").assertDoesNotExist()
        composeRule.onNodeWithText("Syncing wallets…").assertDoesNotExist()
    }

    @Test
    fun configuredUtxoViewRendersOnlyThatViewWithoutARedundantModeIndicator() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val view = mutableStateOf(UtxoView.BUBBLES)
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = view.value,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_bubble_view").assertIsDisplayed()
        composeRule.onNodeWithTag("utxo_list_view").assertDoesNotExist()
        composeRule.onNodeWithTag("utxo_view_mode_indicator").assertDoesNotExist()
        composeRule.onNodeWithText("dust, under 5,000 sats").assertDoesNotExist()

        composeRule.runOnIdle { view.value = UtxoView.LIST }
        composeRule.onNodeWithTag("utxo_list_view").assertIsDisplayed()
        composeRule.onNodeWithTag("utxo_bubble_view").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("UTXO view: List").assertDoesNotExist()
        database.close()
    }

    @Test
    fun utxoTabShowsConfirmedAndPendingOutputCountsAboveTheReceiveAction() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(WatchedKeyEntity("wallet", "Wallet", "redacted", ScriptType.NATIVE_SEGWIT, 1L))
            val addressId = database.derivedAddressDao().upsert(
                DerivedAddressEntity(
                    keyId = "wallet",
                    chain = AddressChain.EXTERNAL,
                    derivationIndex = 0,
                    address = "bc1qutxocountaddress",
                    isUsed = true,
                    isConfirmedUnused = false,
                ),
            )
            database.utxoDao().upsertAll(
                listOf(
                    UtxoEntity(addressId = addressId, txid = "confirmed-one", vout = 0, valueSats = 50_000, confirmations = 1),
                    UtxoEntity(addressId = addressId, txid = "confirmed-two", vout = 1, valueSats = 25_000, confirmations = 6),
                    UtxoEntity(addressId = addressId, txid = "pending", vout = 0, valueSats = 10_000, confirmations = 0),
                ),
            )
        }
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.LIST,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_confirmation_summary").assertIsDisplayed()
        composeRule.onNodeWithText("2 confirmed · 1 pending").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_receive_action").assertIsDisplayed()
        database.close()
    }

    @Test
    fun walletDetailTabsCanBeChangedWithHorizontalSwipes() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.BUBBLES,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithTag("wallet_detail_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("utxo_bubble_view").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_detail_pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithContentDescription("Transactions tab, selected").assertIsDisplayed()
        database.close()
    }

    @Test
    fun transactionTabPagesTwentyFiveRowsAndExposesAccessiblePreviousAndNextControls() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(WatchedKeyEntity("wallet", "Wallet", "redacted", ScriptType.NATIVE_SEGWIT, 1L))
            val addressId = database.derivedAddressDao().upsert(
                DerivedAddressEntity(keyId = "wallet", chain = AddressChain.EXTERNAL, derivationIndex = 0, address = "bc1qtransactionpagination", isUsed = true, isConfirmedUnused = false),
            )
            database.addressHistoryDao().upsertAll(
                (1..26).map { height ->
                    AddressHistoryEntity(addressId = addressId, txid = "pagination-$height", confirmations = 1, blockHeight = height, valueSats = height.toLong())
                },
            )
            database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "pagination-26", "Transaction 26"))
            database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "pagination-1", "Transaction 1"))
        }
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.LIST,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("Transaction 26").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithContentDescription("Received transaction").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithTag("transaction_timestamp", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithTag("transaction_confirmations", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithTag("transaction_direction_icon_incoming", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("transaction_list").performScrollToNode(hasTestTag("transaction_pagination_range"))
        composeRule.onNodeWithTag("transaction_pagination_range").assertIsDisplayed()
        composeRule.onNodeWithText("1–25 of 26").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_pagination_previous").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous page").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next page").assertIsDisplayed()
        composeRule.onNodeWithText("Previous").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
        composeRule.onNodeWithTag("transaction_pagination_next").assertIsEnabled().performClick()
        composeRule.onNodeWithText("26–26 of 26").assertIsDisplayed()
        composeRule.onNodeWithText("Transaction 1").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_pagination_previous").assertIsEnabled()
        composeRule.onNodeWithTag("transaction_pagination_next").assertIsNotEnabled()
        database.close()
    }

    @Test
    fun singleAddressCachedPagesDoNotFetchUntilLoadMoreIsTapped() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(
                WatchedKeyEntity(
                    id = "single-wallet",
                    label = "Single address",
                    keyMaterial = "redacted",
                    scriptType = ScriptType.NATIVE_SEGWIT,
                    dateAdded = 1L,
                    targetType = WatchTargetType.SINGLE_ADDRESS,
                ),
            )
            val addressId = database.derivedAddressDao().upsert(
                DerivedAddressEntity(
                    keyId = "single-wallet",
                    chain = AddressChain.EXTERNAL,
                    derivationIndex = 0,
                    address = "bc1qsingleaddresspagination",
                    isUsed = true,
                    isConfirmedUnused = false,
                    historyRemoteCount = 50,
                    historyNextCursor = "older-history",
                    historyComplete = false,
                ),
            )
            database.addressHistoryDao().upsertAll(
                (1..50).map { height ->
                    AddressHistoryEntity(addressId = addressId, txid = "single-page-$height", confirmations = 1, blockHeight = height, valueSats = height.toLong())
                },
            )
        }
        var loadMoreCalls = 0
        assertEquals(50, runBlocking { database.walletScreenDao().observeTransactionCount("single-wallet").first() })
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "single-wallet",
                    defaultUtxoView = UtxoView.LIST,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                    onLoadMoreHistory = {
                        loadMoreCalls += 1
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText("Address").assertIsDisplayed()
        composeRule.onNodeWithTag("transaction_list").performScrollToNode(hasTestTag("transaction_pagination_next"))
        composeRule.onNodeWithTag("transaction_pagination_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("transaction_list").performScrollToNode(hasTestTag("transaction_pagination_next"))
        composeRule.onNodeWithTag("transaction_pagination_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("transaction_empty_history_page").assertIsDisplayed()
        assertEquals(0, loadMoreCalls)
        composeRule.onNodeWithTag("transaction_history_load_more").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, loadMoreCalls)
        database.close()
    }

    @Test
    fun groupWalletUtxoTabShowsAggregatedConfirmationSummary() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedGroupUtxos(database)
        composeRule.setContent {
            GlanceTheme {
                GroupDetailScreen(
                    database = database,
                    groupId = "group",
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onReceive = {},
                    onWalletSettings = {},
                    onTransaction = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_confirmation_summary").assertIsDisplayed()
        composeRule.onNodeWithText("2 confirmed · 1 pending").assertIsDisplayed()
        composeRule.onNodeWithTag("group_wallet_receive_action").assertIsDisplayed()
        database.close()
    }

    @Test
    fun groupWalletDetailTabsCanBeChangedWithHorizontalSwipes() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedGroupUtxos(database)
        composeRule.setContent {
            GlanceTheme {
                GroupDetailScreen(
                    database = database,
                    groupId = "group",
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onReceive = {},
                    onWalletSettings = {},
                    onTransaction = {},
                )
            }
        }

        composeRule.onNodeWithTag("group_wallet_detail_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("group_utxo_bubble_view").assertIsDisplayed()
        composeRule.onNodeWithTag("group_wallet_detail_pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithContentDescription("Transactions tab, selected").assertIsDisplayed()
        database.close()
    }

    @Test
    fun pullingDownOnUtxoBubblesRefreshesTheWallet() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedUtxo(database)
        var refreshes = 0
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.BUBBLES,
                    syncState = WalletSyncState.Idle,
                    onRefresh = { refreshes++ },
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_bubble_view").performTouchInput {
            swipe(Offset(200f, 80f), Offset(200f, 700f), durationMillis = 500)
        }
        composeRule.runOnIdle { assertTrue("Expected one bubble refresh, got $refreshes", refreshes == 1) }
        database.close()
    }

    @Test
    fun tappingAUtxoBubbleOpensTheSharedDetailsSheet() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedUtxo(database)
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.BUBBLES,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_bubble_view").performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }

        composeRule.onNodeWithText("Unspent output").assertIsDisplayed()
        composeRule.onNodeWithTag("utxo_facts_card").assertIsDisplayed()
        database.close()
    }

    @Test
    fun pullingDownOnTheUtxoListRefreshesTheWallet() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedUtxo(database)
        var refreshes = 0
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.LIST,
                    syncState = WalletSyncState.Idle,
                    onRefresh = { refreshes++ },
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_list_view").performTouchInput { swipeDown() }
        composeRule.runOnIdle { assertTrue("Expected one list refresh, got $refreshes", refreshes == 1) }
        database.close()
    }

    @Test
    fun utxoListCardOpensAnAmountLedDetailsSheetWithSafeCopyActions() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(WatchedKeyEntity("wallet", "Wallet", "redacted", ScriptType.NATIVE_SEGWIT, 1L))
            val addressId = database.derivedAddressDao().upsert(
                DerivedAddressEntity(
                    keyId = "wallet",
                    chain = AddressChain.EXTERNAL,
                    derivationIndex = 7,
                    address = "bc1qutxodetailsaddress",
                    isUsed = true,
                    isConfirmedUnused = false,
                ),
            )
            database.utxoDao().upsertAll(listOf(UtxoEntity(addressId = addressId, txid = "a3f9123402c1", vout = 4, valueSats = 42, confirmations = 0)))
            database.labelDao().upsert(LabelEntity(LabelReferenceType.ADDRESS, "bc1qutxodetailsaddress", "Coffee UTXO"))
        }
        composeRule.setContent {
            GlanceTheme {
                KeyDetailScreen(
                    database = database,
                    keyId = "wallet",
                    defaultUtxoView = UtxoView.LIST,
                    syncState = WalletSyncState.Idle,
                    onRefresh = {},
                    onBack = {},
                    onTransaction = {},
                    onReceive = {},
                )
            }
        }

        composeRule.onNodeWithText("UTXOs").performClick()
        composeRule.onNodeWithTag("utxo_card_1").assertIsDisplayed()
        composeRule.onNodeWithText("Coffee UTXO").assertIsDisplayed()
        composeRule.onNodeWithText("Pending").assertIsDisplayed()
        composeRule.onNodeWithTag("utxo_card_1").performClick()
        composeRule.onNodeWithText("Unspent output").assertIsDisplayed()
        composeRule.onNodeWithTag("utxo_facts_card").assertIsDisplayed()
        composeRule.onNodeWithText("a3f9…02c1:4").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy transaction ID").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy address").assertIsDisplayed()
        database.close()
    }

    @Test
    fun failedWalletSyncOffersARetry() {
        composeRule.setContent { GlanceTheme { WalletSyncStatus(WalletSyncState.Failed, {}) } }

        composeRule.onNodeWithTag("wallet_sync_failed").assertIsDisplayed()
        composeRule.onNodeWithText("Sync paused. Your cached wallet data remains available.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry sync").assertIsDisplayed()
    }

    @Test
    fun walletSettingsRenamesAndConfirmsDeletion() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(
                WatchedKeyEntity("wallet", "Original", "redacted", ScriptType.NATIVE_SEGWIT, 1L),
            )
        }
        var returned = false
        var deleted = false
        composeRule.setContent {
            GlanceTheme {
                WalletSettingsScreen(
                    database = database,
                    keyId = "wallet",
                    preferences = preferences,
                    onBack = { returned = true },
                    onDeleted = { deleted = true },
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("wallet_settings_group_wallet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("wallet_settings_group_wallet").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_settings_group_danger").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_setting_utxo_view").performClick()
        composeRule.onNodeWithText("List").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.watchedKeyDao().findById("wallet")?.utxoView == UtxoView.LIST.name }
        }
        composeRule.waitUntil(5_000) {
            runBlocking { preferences.data.first().utxoView == UtxoView.LIST }
        }
        composeRule.onNodeWithTag("wallet_setting_dust_threshold").performTextReplacement("0")
        composeRule.onNodeWithTag("save_wallet_dust_threshold").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.watchedKeyDao().findById("wallet")?.dustThresholdSats == 0L }
        }
        composeRule.onNodeWithTag("wallet_name").performTextReplacement("Renamed")
        composeRule.onNodeWithText("Save name").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.watchedKeyDao().findById("wallet")?.label == "Renamed" }
        }
        assertTrue(returned)
        composeRule.onNodeWithText("Delete watched key").performClick()
        composeRule.onNodeWithText("Delete watched key?").assertIsDisplayed()
        composeRule.onNodeWithText("No").performClick()
        composeRule.onNodeWithText("Delete watched key").performClick()
        composeRule.onNodeWithText("Yes").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.watchedKeyDao().findById("wallet") == null }
        }
        assertTrue(deleted)
    }

    @Test
    fun transactionLabelSavesExplicitlyAndClearsWhenSavedBlank() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(
                WatchedKeyEntity("wallet", "Wallet", "redacted", ScriptType.NATIVE_SEGWIT, 1L),
            )
            val addressId = database.derivedAddressDao().upsert(
                DerivedAddressEntity(
                    keyId = "wallet",
                    chain = AddressChain.EXTERNAL,
                    derivationIndex = 0,
                    address = "bc1qtesttransactionlabel",
                    isUsed = true,
                    isConfirmedUnused = false,
                ),
            )
            database.addressHistoryDao().upsertAll(
                listOf(AddressHistoryEntity(addressId = addressId, txid = "a3f9123402c1", confirmations = 1, blockHeight = 1, valueSats = 42)),
            )
        }
        val historyId = runBlocking { database.walletScreenDao().observeTransactionPage("wallet", limit = 20, offset = 0).first().single().historyId }
        composeRule.setContent {
            GlanceTheme {
                TransactionDetailScreen(database, historyId, ExplorerPreset.MEMPOOL_SPACE, onBack = {})
            }
        }

        composeRule.onNodeWithTag("transaction_facts_card").assertIsDisplayed()
        composeRule.onNodeWithText("a3f9…02c1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy transaction ID").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy address").assertIsDisplayed()
        composeRule.onNodeWithTag("save_transaction_label").assertIsNotEnabled()
        composeRule.onNodeWithTag("transaction_label").performTextReplacement("Coffee")
        composeRule.onNodeWithTag("save_transaction_label").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.labelDao().find(app.glance.wallet.core.data.db.LabelReferenceType.TRANSACTION, "a3f9123402c1")?.text == "Coffee" }
        }
        composeRule.onNodeWithTag("transaction_label").performTextReplacement("")
        composeRule.onNodeWithTag("save_transaction_label").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { database.labelDao().find(app.glance.wallet.core.data.db.LabelReferenceType.TRANSACTION, "a3f9123402c1") == null }
        }
    }

    @Test
    fun receiveScreenUsesTheReferenceHierarchyAndSafeAddressDisplay() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.watchedKeyDao().upsert(
                WatchedKeyEntity("receive-wallet", "Savings", "redacted", ScriptType.NATIVE_SEGWIT, 1L),
            )
            database.derivedAddressDao().upsert(
                DerivedAddressEntity(
                    keyId = "receive-wallet",
                    chain = AddressChain.EXTERNAL,
                    derivationIndex = 0,
                    address = "bc1q9h2k3f7aaaaaaaaaaaaaas8x4p9wq",
                    isUsed = false,
                    isConfirmedUnused = true,
                ),
            )
        }
        composeRule.setContent { GlanceTheme { ReceiveScreen(database, "receive-wallet", onBack = {}) } }

        composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
        composeRule.onNodeWithText("Next unused address · Savings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bitcoin receive QR code").assertIsDisplayed()
        composeRule.onNodeWithTag("receive_address").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bitcoin receive address").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy receive address").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share receive address").assertIsDisplayed()
        database.close()
    }

    @Test
    fun groupTransactionDetailShowsStandardFactsAndFormatEntries() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val transactionId = "a3f9123402c1"
        runBlocking {
            val group = WalletGroupEntity("group", "Savings", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT)
            database.walletGroupDao().insert(group)
            database.watchedKeyDao().upsert(WatchedKeyEntity("legacy", "Savings", "redacted", ScriptType.LEGACY, 1L, walletGroupId = group.id))
            database.watchedKeyDao().upsert(WatchedKeyEntity("native", "Savings", "redacted", ScriptType.NATIVE_SEGWIT, 1L, walletGroupId = group.id))
            val legacyAddress = database.derivedAddressDao().upsert(DerivedAddressEntity(keyId = "legacy", chain = AddressChain.EXTERNAL, derivationIndex = 0, address = "1legacygroupaddress", isUsed = true, isConfirmedUnused = false))
            val nativeAddress = database.derivedAddressDao().upsert(DerivedAddressEntity(keyId = "native", chain = AddressChain.EXTERNAL, derivationIndex = 0, address = "bc1qnativegroupaddress", isUsed = true, isConfirmedUnused = false))
            database.addressHistoryDao().upsertAll(listOf(
                AddressHistoryEntity(addressId = legacyAddress, txid = transactionId, confirmations = 3, blockHeight = 800_000, valueSats = 42),
                AddressHistoryEntity(addressId = nativeAddress, txid = transactionId, confirmations = 3, blockHeight = 800_000, valueSats = 58),
            ))
            database.blockTimestampCacheDao().upsert(BlockTimestampCacheEntity(800_000, 1_700_000_000L))
        }
        composeRule.setContent {
            GlanceTheme {
                GroupTransactionDetailScreen(database, "group", transactionId, ExplorerPreset.BLOCKSTREAM, onBack = {})
            }
        }

        composeRule.onNodeWithTag("group_transaction_facts_card").assertIsDisplayed()
        composeRule.onNode(hasText("100 sats", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Legacy").assertIsDisplayed()
        composeRule.onNodeWithText("Native SegWit").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Copy address").assertCountEquals(2)
        composeRule.onNodeWithTag("group_transaction_label").performTextReplacement("Shared label")
        composeRule.onNodeWithTag("save_group_transaction_label").assertIsEnabled()
    }

    @Test
    fun groupFormatRemovalActionIsAvailable() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.walletGroupDao().insert(WalletGroupEntity("group", "Savings", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT))
            database.watchedKeyDao().upsert(WatchedKeyEntity("legacy", "Savings", "redacted", ScriptType.LEGACY, 1L, walletGroupId = "group"))
            database.watchedKeyDao().upsert(WatchedKeyEntity("native", "Savings", "redacted", ScriptType.NATIVE_SEGWIT, 1L, walletGroupId = "group"))
        }
        composeRule.setContent { GlanceTheme { GroupWalletSettingsScreen(database, "group", onBack = {}, onDeleted = {}) } }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remove_group_format_legacy").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("remove_group_format_legacy").assertIsEnabled()
    }

    @Test
    fun groupWalletSettingsDoesNotTreatInitialDatabaseLoadAsDeletion() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.walletGroupDao().insert(
                WalletGroupEntity("group", "Savings", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT),
            )
        }
        var deleted = false
        composeRule.setContent {
            GlanceTheme {
                GroupWalletSettingsScreen(database, "group", onBack = {}, onDeleted = { deleted = true })
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("group_wallet_settings_group_wallet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertFalse("Loading an existing group must not navigate Home", deleted) }
        database.close()
    }

    @Test
    fun missingGroupWalletSettingsReturnsToHomeAfterLookup() {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var deleted = false
        composeRule.setContent {
            GlanceTheme {
                GroupWalletSettingsScreen(database, "missing", onBack = {}, onDeleted = { deleted = true })
            }
        }

        composeRule.waitUntil(5_000) { deleted }
        database.close()
    }

    private fun seedUtxo(database: GlanceDatabase) = runBlocking {
        database.watchedKeyDao().upsert(WatchedKeyEntity("wallet", "Wallet", "redacted", ScriptType.NATIVE_SEGWIT, 1L))
        val addressId = database.derivedAddressDao().upsert(
            DerivedAddressEntity(
                keyId = "wallet",
                chain = AddressChain.EXTERNAL,
                derivationIndex = 0,
                address = "bc1qgestureutxoaddress",
                isUsed = true,
                isConfirmedUnused = false,
            ),
        )
        database.utxoDao().upsertAll(
            listOf(UtxoEntity(addressId = addressId, txid = "redacted-utxo-transaction", vout = 0, valueSats = 50_000, confirmations = 1)),
        )
    }

    private fun seedGroupUtxos(database: GlanceDatabase) = runBlocking {
        database.walletGroupDao().insert(
            WalletGroupEntity("group", "Savings", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT),
        )
        database.watchedKeyDao().upsert(
            WatchedKeyEntity("native", "", "redacted", ScriptType.NATIVE_SEGWIT, 1L, walletGroupId = "group"),
        )
        val addressId = database.derivedAddressDao().upsert(
            DerivedAddressEntity(keyId = "native", chain = AddressChain.EXTERNAL, derivationIndex = 0, address = "bc1qgrouputxoaddress", isUsed = true, isConfirmedUnused = false),
        )
        database.utxoDao().upsertAll(
            listOf(
                UtxoEntity(addressId = addressId, txid = "group-confirmed-one", vout = 0, valueSats = 50_000, confirmations = 1),
                UtxoEntity(addressId = addressId, txid = "group-confirmed-two", vout = 1, valueSats = 25_000, confirmations = 6),
                UtxoEntity(addressId = addressId, txid = "group-pending", vout = 0, valueSats = 10_000, confirmations = 0),
            ),
        )
    }

}
