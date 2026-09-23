package app.glance.wallet.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

enum class FiatProvider { MEMPOOL_SPACE }

data class FiatQuote(
    val provider: FiatProvider,
    val currency: String,
    val price: Double,
    val timestampEpochSeconds: Long,
    val isCached: Boolean = false,
)

interface FiatPriceProvider {
    val provider: FiatProvider
    fun currentPrice(currency: String): FiatQuote
    fun historicalPrices(currency: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<FiatQuote>
}

/** Compatibility name retained while callers migrate to the provider terminology. */
typealias FiatPriceClient = FiatPriceProvider

class MempoolFiatPriceClient(
    baseUrl: String = "https://mempool.space/api/",
    private val clientFactorySource: NetworkClientFactorySource,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
) : FiatPriceClient {
    override val provider = FiatProvider.MEMPOOL_SPACE
    private val baseUrl = baseUrl.toHttpUrl()

    override fun currentPrice(currency: String): FiatQuote {
        require(currency.uppercase() in SUPPORTED_CURRENCIES) { "Unsupported fiat currency" }
        val body = get("v1/prices")
        val price = Json.parseToJsonElement(body).jsonObject[currency.uppercase()]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?.takeIf(::isUsablePrice)
            ?: throw NetworkException("Fiat provider returned an invalid price")
        return FiatQuote(provider, currency.uppercase(), price, clock())
    }

    override fun historicalPrices(currency: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<FiatQuote> {
        require(currency.uppercase() in SUPPORTED_CURRENCIES) { "Unsupported fiat currency" }
        require(fromEpochSeconds <= toEpochSeconds) { "Invalid price range" }
        if (fromEpochSeconds == toEpochSeconds) {
            val body = get("v1/historical-price") {
                addQueryParameter("currency", currency.uppercase())
                addQueryParameter("timestamp", fromEpochSeconds.toString())
            }
            val price = Json.parseToJsonElement(body).jsonObject["prices"]?.jsonObject
                ?.get(currency.uppercase())?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf(::isUsablePrice)
                ?: throw NetworkException("Fiat provider returned an invalid historical price")
            return listOf(FiatQuote(provider, currency.uppercase(), price, fromEpochSeconds))
        }
        // The public endpoint returns its complete native history when no query is supplied.
        // Fetching it once avoids a rate-limit-prone request for every rendered chart sample.
        val prices = Json.parseToJsonElement(get("v1/historical-price")).jsonObject["prices"]?.jsonArray
            ?: throw NetworkException("Fiat provider returned invalid historical prices")
        val series = prices.mapNotNull { point ->
            val values = point.jsonObject
            val time = values["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val price = values[currency.uppercase()]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?.takeIf(::isUsablePrice) ?: return@mapNotNull null
            FiatQuote(provider, currency.uppercase(), price, time)
        }.sortedBy(FiatQuote::timestampEpochSeconds)
        val prior = series.lastOrNull { it.timestampEpochSeconds <= fromEpochSeconds }
        return (listOfNotNull(prior) + series.filter { it.timestampEpochSeconds in fromEpochSeconds..toEpochSeconds })
            .distinctBy(FiatQuote::timestampEpochSeconds)
    }

    private fun get(path: String, configure: okhttp3.HttpUrl.Builder.() -> Unit = {}): String =
        execute(baseUrl.newBuilder().addPathSegments(path).apply(configure).build())

    private fun execute(url: okhttp3.HttpUrl): String = clientFactorySource.current().okHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw NetworkException("Fiat provider request failed (HTTP ${response.code})")
        response.body?.string() ?: throw NetworkException("Fiat provider response body is empty")
    }

    companion object {
        val SUPPORTED_CURRENCIES = setOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY")
    }
}

internal fun isUsablePrice(price: Double): Boolean = price.isFinite() && price > 0.0

class NetworkException(message: String) : RuntimeException(message)
