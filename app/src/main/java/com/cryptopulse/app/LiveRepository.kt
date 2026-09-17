package com.cryptopulse.app

import android.util.Log
import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {

private val spotHosts = listOf(
    "https://api.binance.com/",
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

private fun createSpotApi(
    host: String
): BinanceSpotApi {

    return Retrofit.Builder()
        .baseUrl(host)
        .addConverterFactory(
            MoshiConverterFactory.create()
        )
        .build()
        .create(BinanceSpotApi::class.java)
}

/**
 * دریافت کندل از چند endpoint مختلف Binance.
 *
 * اگر endpoint اصلی در دسترس نباشد،
 * api1 تا api4 نیز امتحان می‌شوند.
 */
private suspend fun loadKlines(
    symbol: String,
    interval: String,
    limit: Int
): List<Candle> {

    var lastError: Throwable? = null

    for (host in spotHosts) {

        try {

            val api =
                createSpotApi(host)

            val response =
                api.klines(
                    symbol,
                    interval,
                    limit
                )

            val raw =
                response.string()

            if (raw.isBlank()) {
                throw IllegalStateException(
                    "Binance پاسخ خالی برگرداند."
                )
            }

            val rows =
                parseKlines(raw)

            if (rows.isNotEmpty()) {
                return rows
            }

            throw IllegalStateException(
                "Binance کندلی برای $symbol برنگرداند."
            )

        } catch (e: Throwable) {

            lastError = e

            Log.w(
                "CryptoAnalysis",
                "Kline failed on $host for $symbol: ${e.message}"
            )
        }
    }

    throw IllegalStateException(
        "دریافت اطلاعات $symbol از Binance ناموفق بود. " +
            "آخرین خطا: ${lastError?.message ?: "نامشخص"}"
    )
}

/**
 * نسخه سبک‌تر برای اسکن بازار
 */
suspend fun loadForScan(
    symbol: String
): LiveSnapshot =
    withContext(Dispatchers.IO) {

        val normalized =
            normalizeSymbol(symbol)

        val rows =
            loadKlines(
                normalized,
                "1d",
                180
            )

        val btcRows =
            if (normalized == "BTCUSDT") {
                rows
            } else {
                runCatching {
                    loadKlines(
                        "BTCUSDT",
                        "1d",
                        180
                    )
                }.getOrDefault(emptyList())
            }

        val oi =
            runCatching {
                futures
                    .openInterest(normalized)
                    .openInterest
                    .toDouble()
            }.getOrNull()

        val funding =
            runCatching {
                futures
                    .fundingRate(
                        normalized,
                        5
                    )
                    .lastOrNull()
                    ?.fundingRate
                    ?.toDouble()
            }.getOrNull()

        val oiHist =
            runCatching {
                futures.openInterestHistory(
                    normalized,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

        val ls =
            runCatching {
                futures.longShort(
                    normalized,
                    "4h",
                    15
                )
            }.getOrDefault(emptyList())

        val taker =
            runCatching {
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

/**
 * نسخه کامل برای تحلیل یک ارز.
 *
 * 800 کندل روزانه تقریباً بیش از 2 سال داده است.
 */
suspend fun load(
    symbol: String
): LiveSnapshot =
    withContext(Dispatchers.IO) {

        val normalized =
            normalizeSymbol(symbol)

        val rows =
            loadKlines(
                normalized,
                "1d",
                800
            )

        if (rows.size < 60) {
            throw IllegalStateException(
                "برای $normalized فقط ${rows.size} کندل دریافت شد؛ " +
                    "حداقل 60 کندل برای تحلیل لازم است."
            )
        }

        val btcRows =
            if (normalized == "BTCUSDT") {
                rows
            } else {
                loadKlines(
                    "BTCUSDT",
                    "1d",
                    800
                )
            }

        val oi =
            runCatching {
                futures
                    .openInterest(normalized)
                    .openInterest
                    .toDouble()
            }.getOrNull()

        val funding =
            runCatching {
                futures
                    .fundingRate(
                        normalized,
                        10
                    )
                    .lastOrNull()
                    ?.fundingRate
                    ?.toDouble()
            }.getOrNull()

        val oiHist =
            runCatching {
                futures.openInterestHistory(
                    normalized,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

        val ls =
            runCatching {
                futures.longShort(
                    normalized,
                    "4h",
                    30
                )
            }.getOrDefault(emptyList())

        val taker =
            runCatching {
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

/**
 * تبدیل ورودی کاربر به نماد استاندارد Binance.
 */
private fun normalizeSymbol(
    input: String
): String {

    return input
        .trim()
        .uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
        .let { symbol ->

            when {

                symbol.endsWith("USDT") ->
                    symbol

                symbol.endsWith("USD") ->
                    symbol.removeSuffix("USD") + "USDT"

                else ->
                    symbol + "USDT"
            }
        }
}

/**
 * تبدیل Kline خام Binance به Candle.
 *
 * 0 = open time
 * 1 = open
 * 2 = high
 * 3 = low
 * 4 = close
 * 5 = volume
 * 6 = close time
 * 7 = quote asset volume
 */
private fun parseKlines(
    raw: String
): List<Candle> {

    val array =
        JSONArray(raw)

    val out =
        ArrayList<Candle>(
            array.length()
        )

    for (i in 0 until array.length()) {

        val row =
            array.getJSONArray(i)

        if (row.length() < 8) {
            continue
        }

        val openTime =
            row.optLong(
                0,
                0L
            )

        val close =
            row.getString(4)
                .toDoubleOrNull()
                ?: continue

        val high =
            row.getString(2)
                .toDoubleOrNull()
                ?: continue

        val low =
            row.getString(3)
                .toDoubleOrNull()
                ?: continue

        val volume =
            row.getString(5)
                .toDoubleOrNull()
                ?: 0.0

        val quoteVolume =
            row.optString(
                7,
                ""
            ).toDoubleOrNull()
                ?: (volume * close)

        if (
            close <= 0.0 ||
            high <= 0.0 ||
            low <= 0.0
        ) {
            continue
        }

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
val openInterestHistory:
List<OpenInterestHistDto> = emptyList(),
val longShortHistory:
List<LongShortDto> = emptyList(),
val takerVolumeHistory:
List<TakerVolumeDto> = emptyList(),
val btcCandles:
List<Candle> = emptyList()
)
