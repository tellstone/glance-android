package app.glance.wallet

import app.glance.wallet.core.data.db.FiatPriceCacheEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.network.FiatPriceClient
import app.glance.wallet.core.network.FiatProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** App-layer bridge: core-network stays Room-free while quote data remains encrypted and durable. */
internal class FiatPriceStore(
    private val database: GlanceDatabase,
    private val mempoolClearnetClient: FiatPriceClient,
    private val mempoolOnionClient: FiatPriceClient,
) {
    private val currentProvider = MutableStateFlow(FiatProvider.MEMPOOL_SPACE)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(currency: String): Flow<List<FiatPriceCacheEntity>> =
        currentProvider.flatMapLatest { provider ->
            database.fiatPriceCacheDao().observeForProviderAndCurrency(provider.storageName, currency)
        }

    fun observeHistorical(currency: String): Flow<List<FiatPriceCacheEntity>> =
        database.fiatPriceCacheDao().observeForProviderAndCurrency(FiatProvider.MEMPOOL_SPACE.storageName, currency)

    suspend fun refreshCurrent(currency: String, useOnion: Boolean): Boolean = request(useOnion) { client ->
        withContext(Dispatchers.IO) { client.currentPrice(currency) }
    }.mapCatching { quote ->
        currentProvider.value = quote.provider
        database.fiatPriceCacheDao().upsert(
            FiatPriceCacheEntity(provider = quote.provider.storageName, currency = currency, timestamp = quote.timestampEpochSeconds, price = quote.price),
        )
    }.isSuccess

    /** Fetches a provider-native series once, then persists only chart buckets. */
    suspend fun ensureHistorical(
        currency: String,
        timestamps: List<Long>,
        useOnion: Boolean,
    ): HistoricalFiatStatus {
        if (timestamps.isEmpty()) return HistoricalFiatStatus.Complete
        val provider = FiatProvider.MEMPOOL_SPACE
        val cached = database.fiatPriceCacheDao()
            .cachedTimestamps(provider.storageName, currency, timestamps)
            .toSet()
        val missing = timestamps.filterNot(cached::contains)
        if (missing.isEmpty()) return HistoricalFiatStatus.Complete
        val client = mempoolClient(useOnion)
        val quotes = runCatching {
            withContext(Dispatchers.IO) {
                if (missing.size == 1) {
                    client.historicalPrices(currency, missing.single(), missing.single())
                } else {
                    client.historicalPrices(currency, historicalPriceRequestStart(missing.min()), missing.max())
                }
            }
        }.getOrElse {
            return if (cached.isEmpty()) HistoricalFiatStatus.Unavailable else HistoricalFiatStatus.Partial
        }
        return runCatching {
            missing.forEach { timestamp ->
                val quote = quotes.lastOrNull { it.timestampEpochSeconds <= timestamp }
                    ?: throw IllegalStateException("Historical fiat quote is unavailable")
                database.fiatPriceCacheDao().upsert(
                    FiatPriceCacheEntity(provider = quote.provider.storageName, currency = currency, timestamp = timestamp, price = quote.price),
                )
            }
        }.fold(
            onSuccess = { HistoricalFiatStatus.Complete },
            onFailure = { if (cached.isEmpty()) HistoricalFiatStatus.Unavailable else HistoricalFiatStatus.Partial },
        )
    }

    private suspend fun <T> request(useOnion: Boolean, block: suspend (FiatPriceClient) -> T): Result<T> {
        return runCatching { block(mempoolClient(useOnion)) }
    }

    private fun mempoolClient(useOnion: Boolean): FiatPriceClient = if (useOnion) mempoolOnionClient else mempoolClearnetClient

}

internal enum class HistoricalFiatStatus { Loading, Complete, Partial, Unavailable }

/** All supported chart ranges use Mempool's keyless provider-native history response. */
internal fun historicalProviderFor(
    currency: String,
): FiatProvider? {
    val mempoolSupported = currency.uppercase() in MEMPOOL_CURRENCIES
    return FiatProvider.MEMPOOL_SPACE.takeIf { mempoolSupported }
}

internal fun List<FiatPriceCacheEntity>.latestPrice(): FiatPriceCacheEntity? = maxByOrNull(FiatPriceCacheEntity::timestamp)

/** Provider series are interval-aligned; include a prior sample for the first chart bucket. */
internal fun historicalPriceRequestStart(firstBucketSeconds: Long): Long =
    (firstBucketSeconds - HISTORICAL_PRICE_LOOKBACK_SECONDS).coerceAtLeast(0L)

private const val HISTORICAL_PRICE_LOOKBACK_SECONDS = 86_400L
private val MEMPOOL_CURRENCIES = setOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY")

private val FiatProvider.storageName: String get() = "mempool_space"
