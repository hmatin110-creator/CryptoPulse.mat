package com.cryptopulse.app

import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {

    private val spot =
        Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BinanceSpotApi::class.java)

    private val futures =
        Retrofit.Builder()
            .baseUrl("https://fapi.binance.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BinanceFuturesApi::class.java)

    /**
     * نسخه سبک‌تر برای Scanner
     */
    suspend fun loadForScan(symbol: String): LiveSnapshot =
        withContext(Dispatchers.IO) {

            val normalized = normalizeSymbol(symbol)

            val rows = runCatching {
                parseKlines(
                    spot.klines(
                        normalized,
                        "1d",
                        180
                    ).string()
                )
            }.getOrDefault(emptyList())

            val btcRows =
                if (normalized == "BTCUSDT") {
                    rows
                } else {
                    runCatching {
                        parseKlines(
                            spot.klines(
                                "BTCUSDT",
                                "1d",
                                180
                            ).string()
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

            val pair = normalized

            val oiHist = runCatching {
                futures.openInterestHistory(
                    pair,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

            val ls = runCatching {
                futures.longShort(
                    pair,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

            val taker = runCatching {
                futures.takerVolume(
                    pair,
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

    /**
     * نسخه کامل برای تحلیل یک ارز دلخواه
     *
     * 800 کندل روزانه ≈ بیش از 2 سال داده
     *
     * بنابراین:
     * 1D
     * 2D
     * 3D
     * 4D
     * 1W
     * 1M
     * 3M
     * 6M
     *
     * همگی قابل محاسبه هستند.
     */
    suspend fun load(symbol: String): LiveSnapshot =
        withContext(Dispatchers.IO) {

            val normalized = normalizeSymbol(symbol)

            val rows = runCatching {
                parseKlines(
                    spot.klines(
                        normalized,
                        "1d",
                        800
                    ).string()
                )
            }.getOrDefault(emptyList())

            val btcRows =
                if (normalized == "BTCUSDT") {
                    rows
                } else {
                    runCatching {
                        parseKlines(
                            spot.klines(
                                "BTCUSDT",
                                "1d",
                                800
                            ).string()
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

            val pair = normalized

            val oiHist = runCatching {
                futures.openInterestHistory(
                    pair,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

            val ls = runCatching {
                futures.longShort(
                    pair,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

            val taker = runCatching {
                futures.takerVolume(
                    pair,
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

    /**
     * تبدیل ورودی کاربر به نماد استاندارد Binance
     *
     * مثال:
     *
     * btcusdt -> BTCUSDT
     * BTCUSDT -> BTCUSDT
     * ethusdt -> ETHUSDT
     * ETH/USDT -> ETHUSDT
     * ETH-USDT -> ETHUSDT
     */
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

    /**
     * تبدیل Kline خام Binance به Candle
     *
     * ساختار Binance:
     *
     * 0 = open time
     * 1 = open
     * 2 = high
     * 3 = low
     * 4 = close
     * 5 = volume
     * 6 = close time
     * 7 = quote asset volume
     *
     * quoteVolume برای محاسبه ارزش تقریبی جریان پول بسیار مهم است.
     */
    private fun parseKlines(raw: String): List<Candle> {

        val array = JSONArray(raw)

        val out = ArrayList<Candle>(array.length())

        for (i in 0 until array.length()) {

            val row = array.getJSONArray(i)

            val openTime =
                row.optLong(0, 0L)

            val close =
                row.getString(4).toDouble()

            val high =
                row.getString(2).toDouble()

            val low =
                row.getString(3).toDouble()

            val volume =
                row.getString(5).toDouble()

            val quoteVolume =
                row.optString(7, "")
                    .toDoubleOrNull()
                    ?: (volume * close)

            out += Candle(
                close = close,
                high = high,
                low = low,
                volume = volume,
                quoteVolume = quoteVolume,
                openTime = openTime
            )
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
