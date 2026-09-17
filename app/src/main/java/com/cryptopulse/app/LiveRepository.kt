package com.cryptopulse.app

import android.util.Log
import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {

    /*
     * Binance officially provides data-api.binance.vision
     * specifically for public market data.
     *
     * This is important because api.binance.com may return HTTP 451
     * depending on the network/region.
     */
    private val spotHosts = listOf(
        "https://data-api.binance.vision/",
        "https://api.binance.com/",
        "https://api-gcp.binance.com/",
        "https://api1.binance.com/",
        "https://api2.binance.com/",
        "https://api3.binance.com/",
        "https://api4.binance.com/"
    )

    private val futures =
        Retrofit.Builder()
            .baseUrl("https://fapi.binance.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BinanceFuturesApi::class.java)

    private fun createSpotApi(baseUrl: String): BinanceSpotApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BinanceSpotApi::class.java)
    }

    suspend fun loadForScan(symbol: String): LiveSnapshot =
        withContext(Dispatchers.IO) {

            val normalized = normalizeSymbol(symbol)

            val rows = runCatching {
                loadKlines(
                    symbol = normalized,
                    interval = "1d",
                    limit = 180
                )
            }.getOrDefault(emptyList())

            val btcRows =
                if (normalized == "BTCUSDT") {
                    rows
                } else {
                    runCatching {
                        loadKlines(
                            symbol = "BTCUSDT",
                            interval = "1d",
                            limit = 180
                        )
                    }.getOrDefault(emptyList())
                }

            val oi = runCatching {
                futures
                    .openInterest(normalized)
                    .openInterest
                    .toDouble()
            }.getOrNull()

            val funding = runCatching {
                futures
                    .fundingRate(normalized, 5)
                    .lastOrNull()
                    ?.fundingRate
                    ?.toDouble()
            }.getOrNull()

            val oiHist = runCatching {
                futures.openInterestHistory(
                    normalized,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

            val ls = runCatching {
                futures.longShort(
                    normalized,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

            val taker = runCatching {
                futures.takerVolume(
                    normalized,
                    "PERPETUAL",
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

            LiveSnapshot(
                candles = rows,
                openInterest = oi,
                fundingRate = funding,
                openInterestHistory = oiHist,
                longShortHistory = ls,
                takerVolumeHistory = taker,
                btcCandles = btcRows
            )
        }

    suspend fun load(symbol: String): LiveSnapshot =
        withContext(Dispatchers.IO) {

            val normalized = normalizeSymbol(symbol)

            val rows = loadKlines(
                symbol = normalized,
                interval = "1d",
                limit = 800
            )

            if (rows.size < 60) {
                throw IllegalStateException(
                    "داده کافی برای $normalized دریافت نشد. تعداد کندل: ${rows.size}"
                )
            }

            val btcRows =
                if (normalized == "BTCUSDT") {
                    rows
                } else {
                    runCatching {
                        loadKlines(
                            symbol = "BTCUSDT",
                            interval = "1d",
                            limit = 800
                        )
                    }.getOrDefault(emptyList())
                }

            val oi = runCatching {
                futures
                    .openInterest(normalized)
                    .openInterest
                    .toDouble()
            }.getOrNull()

            val funding = runCatching {
                futures
                    .fundingRate(normalized, 10)
                    .lastOrNull()
                    ?.fundingRate
                    ?.toDouble()
            }.getOrNull()

            val oiHist = runCatching {
                futures.openInterestHistory(
                    normalized,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

            val ls = runCatching {
                futures.longShort(
                    normalized,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

            val taker = runCatching {
                futures.takerVolume(
                    normalized,
                    "PERPETUAL",
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

            LiveSnapshot(
                candles = rows,
                openInterest = oi,
                fundingRate = funding,
                openInterestHistory = oiHist,
                longShortHistory = ls,
                takerVolumeHistory = taker,
                btcCandles = btcRows
            )
        }

    private suspend fun loadKlines(
        symbol: String,
        interval: String,
        limit: Int
    ): List<Candle> {

        var lastError: String? = null

        for (host in spotHosts) {

            try {
                Log.d(
                    "CryptoPulse",
                    "Trying Binance host: $host for $symbol"
                )

                val api = createSpotApi(host)

                val response = api.klines(
                    symbol,
                    interval,
                    limit
                )

                val raw = response.string()

                if (raw.isBlank()) {
                    lastError = "$host پاسخ خالی داد"
                    continue
                }

                val rows = parseKlines(raw)

                if (rows.isNotEmpty()) {

                    Log.d(
                        "CryptoPulse",
                        "Binance success: $host -> ${rows.size} candles"
                    )

                    return rows
                }

                lastError = "$host داده معتبر برنگرداند"

            } catch (e: Exception) {

                lastError =
                    "${e.javaClass.simpleName}: ${e.message}"

                Log.w(
                    "CryptoPulse",
                    "Binance failed: $host -> $lastError"
                )
            }
        }

        throw IllegalStateException(
            "دریافت اطلاعات $symbol از Binance ناموفق بود. " +
                    "آخرین خطا: ${lastError ?: "خطای نامشخص"}"
        )
    }

    private fun normalizeSymbol(input: String): String {
        return input
            .trim()
            .uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace(" ", "")
            .let { symbol ->
                when {
                    symbol.endsWith("USDT") -> symbol
                    symbol.endsWith("USD") ->
                        symbol.removeSuffix("USD") + "USDT"
                    else ->
                        symbol + "USDT"
                }
            }
    }

    private fun parseKlines(raw: String): List<Candle> {

        val array = JSONArray(raw)

        val out = ArrayList<Candle>(array.length())

        for (i in 0 until array.length()) {

            try {

                val row = array.getJSONArray(i)

                if (row.length() < 8) {
                    continue
                }

                val openTime =
                    row.optLong(0, 0L)

                val close =
                    row.optString(4, "")
                        .toDoubleOrNull()

                val high =
                    row.optString(2, "")
                        .toDoubleOrNull()

                val low =
                    row.optString(3, "")
                        .toDoubleOrNull()

                val volume =
                    row.optString(5, "")
                        .toDoubleOrNull()

                val quoteVolume =
                    row.optString(7, "")
                        .toDoubleOrNull()

                if (
                    close == null ||
                    high == null ||
                    low == null ||
                    volume == null
                ) {
                    continue
                }

                if (
                    close <= 0.0 ||
                    high <= 0.0 ||
                    low <= 0.0 ||
                    volume < 0.0
                ) {
                    continue
                }

                out += Candle(
                    close = close,
                    high = high,
                    low = low,
                    volume = volume,
                    quoteVolume =
                        quoteVolume
                            ?: (volume * close),
                    openTime = openTime
                )

            } catch (e: Exception) {
                Log.w(
                    "CryptoPulse",
                    "Invalid kline row at index $i",
                    e
                )
            }
        }

        return out
    }
}

data class LiveSnapshot(
    val candles: List<Candle>,
    val openInterest: Double?,
    val fundingRate: Double?,
    val openInterestHistory: List<OpenInterestHistDto> = emptyList(),
    val longShortHistory: List<LongShortDto> = emptyList(),
    val takerVolumeHistory: List<TakerVolumeDto> = emptyList(),
    val btcCandles: List<Candle> = emptyList()
)
