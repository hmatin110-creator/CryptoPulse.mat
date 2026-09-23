package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

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

suspend fun getPrice(
    symbol: String
): Double? = withContext(Dispatchers.IO) {

    val normalized = normalizeSymbol(symbol)

    for (host in hosts) {

        try {

            val api =
                Retrofit.Builder()
                    .baseUrl(host)
                    .addConverterFactory(
                        MoshiConverterFactory.create()
                    )
                    .build()
                    .create(WatchlistPriceApi::class.java)

            val response =
                api.tickerPrice(normalized)

            val price =
                response.price.toDoubleOrNull()

            if (price != null && price > 0.0) {
                return@withContext price
            }

        } catch (_: Exception) {
            // Try the next Binance host.
        }
    }

    return@withContext null
}

suspend fun getPrices(
    symbols: List<String>
): Map<String, Double> =
    withContext(Dispatchers.IO) {

        val result = LinkedHashMap<String, Double>()

        symbols.take(WatchlistRepository.MAX_ITEMS)
            .forEach { symbol ->

                val price = getPrice(symbol)

                if (price != null) {
                    result[normalizeSymbol(symbol)] = price
                }
            }

        result
    }

private fun normalizeSymbol(
    value: String
): String {

    val symbol = value
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
            symbol.removeSuffix("USD") + "USDT"

        else ->
            symbol + "USDT"
    }
}

}

data class WatchlistPriceDto(
val symbol: String,
val price: String
)
