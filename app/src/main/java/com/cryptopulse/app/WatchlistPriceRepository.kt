package com.cryptopulse.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

class WatchlistPriceRepository {

private val hosts = listOf(
    "https://data-api.binance.vision/",
    "https://api.binance.com/",
    "https://api-gcp.binance.com/",
    "https://api1.binance.com/",
    "https://api2.binance.com/",
    "https://api3.binance.com/",
    "https://api4.binance.com/"
)

private fun createApi(
    host: String
): RawWatchlistPriceApi {

    return Retrofit.Builder()
        .baseUrl(host)
        .build()
        .create(
            RawWatchlistPriceApi::class.java
        )
}

suspend fun getPrice(
    symbol: String
): Double? = withContext(Dispatchers.IO) {

    val normalized =
        normalizeSymbol(symbol)

    if (normalized.isBlank()) {
        return@withContext null
    }

    for (host in hosts) {

        try {

            Log.d(
                "Crypto110Price",
                "Trying $host for $normalized"
            )

            val response =
                createApi(host)
                    .tickerPrice(normalized)

            val raw =
                response.string()

            Log.d(
                "Crypto110Price",
                "$host response: $raw"
            )

            if (raw.isBlank()) {
                continue
            }

            val json =
                JSONObject(raw)

            val returnedSymbol =
                json.optString(
                    "symbol",
                    ""
                )

            val priceText =
                json.optString(
                    "price",
                    ""
                )

            val price =
                priceText.toDoubleOrNull()

            if (
                price != null &&
                price > 0.0
            ) {

                Log.d(
                    "Crypto110Price",
                    "SUCCESS $normalized = $price"
                )

                return@withContext price
            }

            Log.w(
                "Crypto110Price",
                "Invalid price for $normalized. " +
                    "symbol=$returnedSymbol " +
                    "price=$priceText"
            )

        } catch (e: Exception) {

            Log.w(
                "Crypto110Price",
                "Failed $host for $normalized: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    Log.e(
        "Crypto110Price",
        "All Binance hosts failed for $normalized"
    )

    return@withContext null
}

suspend fun getPrices(
    symbols: List<String>
): Map<String, Double> =
    withContext(Dispatchers.IO) {

        val result =
            LinkedHashMap<String, Double>()

        symbols
            .take(
                WatchlistRepository.MAX_ITEMS
            )
            .forEach { symbol ->

                val normalized =
                    normalizeSymbol(symbol)

                val price =
                    getPrice(normalized)

                if (
                    price != null &&
                    price > 0.0
                ) {

                    result[normalized] =
                        price
                }
            }

        result
    }

private fun normalizeSymbol(
    value: String
): String {

    val symbol =
        value
            .trim()
            .uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")

    if (symbol.isBlank()) {
        return ""
    }

    return when {

        symbol.endsWith("USDT") ->
            symbol

        symbol.endsWith("USD") ->
            symbol.removeSuffix("USD") +
                "USDT"

        else ->
            symbol + "USDT"
    }
}

}

private interface RawWatchlistPriceApi {

@GET("api/v3/ticker/price")
suspend fun tickerPrice(
    @Query("symbol")
    symbol: String
): ResponseBody

}
