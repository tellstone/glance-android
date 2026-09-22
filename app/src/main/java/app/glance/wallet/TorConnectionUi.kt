package app.glance.wallet

/** PIN is withheld only while the initial Tor-enabled app launch is still bootstrapping. */
internal enum class TorGate { PENDING, OPEN }

internal const val TOR_BOOTSTRAP_GATE_TIMEOUT_MILLIS = 45_000L

internal fun torGate(
    torEnabled: Boolean,
    state: TorState,
    offlineMode: Boolean = false,
    initialBootstrap: Boolean = true,
    bootstrapElapsedMillis: Long = 0L,
): TorGate =
    if (initialBootstrap && bootstrapElapsedMillis < TOR_BOOTSTRAP_GATE_TIMEOUT_MILLIS && !offlineMode && torEnabled && (state == TorState.Disabled || state == TorState.Starting)) TorGate.PENDING else TorGate.OPEN

internal enum class TorConnectionIndicator { OFFLINE, NO_TOR, CONNECTING, CONNECTED }

internal fun torConnectionIndicator(torEnabled: Boolean, state: TorState, offlineMode: Boolean = false): TorConnectionIndicator = when {
    offlineMode -> TorConnectionIndicator.OFFLINE
    !torEnabled -> TorConnectionIndicator.NO_TOR
    state is TorState.Ready -> TorConnectionIndicator.CONNECTED
    state == TorState.Starting || state == TorState.Disabled -> TorConnectionIndicator.CONNECTING
    else -> TorConnectionIndicator.NO_TOR
}

internal enum class TorHeaderIcon { FILLED_SHIELD, OUTLINED_SHIELD, OFFLINE }

internal fun torHeaderIcon(indicator: TorConnectionIndicator): TorHeaderIcon = when (indicator) {
    TorConnectionIndicator.CONNECTED -> TorHeaderIcon.FILLED_SHIELD
    TorConnectionIndicator.OFFLINE -> TorHeaderIcon.OFFLINE
    TorConnectionIndicator.CONNECTING, TorConnectionIndicator.NO_TOR -> TorHeaderIcon.OUTLINED_SHIELD
}
