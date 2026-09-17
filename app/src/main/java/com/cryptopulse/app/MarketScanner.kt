package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import okhttp3.ResponseBody

data class MarketScanResult(
    val top10All: List<ScanCandidate>,
    val top10Top100: List<ScanCandidate>,
    val analyzedCount: Int,
    val universeCount: Int
)

class MarketScanner {

    private val spot = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(BinanceScannerApi::class.java)

    private val exchange = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(BinanceExchangeApi::class.java)

    private val market = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(BinanceSpotApi::class.java)

    private val live = LiveRepository()
    private val news = NewsRepository()

    suspend fun scan(
        universeLimit: Int = 1000,
        technicalLimit: Int = 250,
        enrichLimit: Int = 100
    ): List<ScanCandidate> {
        return scanDetailed(
            universeLimit = universeLimit,
            technicalLimit = technicalLimit,
            enrichLimit = enrichLimit
        ).top10All
    }

    suspend fun scanDetailed(
        universeLimit: Int = 1000,
        technicalLimit: Int = 250,
        enrichLimit: Int = 100
    ): MarketScanResult = withContext(Dispatchers.IO) {

        val exchangeResponse = runCatching {
            exchange.exchangeInfo()
        }.getOrElse {
            return@withContext emptyScanResult()
        }

        val exchangeRaw = runCatching {
            exchangeResponse.string()
        }.getOrDefault("")

        if (exchangeRaw.isBlank()) {
            return@withContext emptyScanResult()
        }

        val allowedSymbols =
            parseTradableUsdtSymbols(exchangeRaw)

        if (allowedSymbols.isEmpty()) {
            return@withContext emptyScanResult()
        }

        val tickerResponse = runCatching {
            spot.ticker24h()
        }.getOrElse {
            return@withContext emptyScanResult()
        }

        val rawTicker = runCatching {
            tickerResponse.string()
        }.getOrDefault("[]")

        val tickers = runCatching {
            JSONArray(rawTicker)
        }.getOrDefault(JSONArray())

        val stableCoins = setOf(
            "USDCUSDT",
            "BUSDUSDT",
            "TUSDUSDT",
            "FDUSDUSDT",
            "USDPUSDT",
            "DAIUSDT",
            "USDEUSDT",
            "USD1USDT"
        )

        val universe = buildUniverse(
            tickers = tickers,
            allowedSymbols = allowedSymbols,
            stableCoins = stableCoins,
            limit = universeLimit
        )

        if (universe.isEmpty()) {
            return@withContext emptyScanResult()
        }

        /*
         * مرحله اول:
         * تحلیل تکنیکال سبک روی کل بازار.
         *
         * این بخش عمداً بدون تغییر نگه داشته شده تا
         * فیلتر اولیه فعلی همان عملکرد قبلی را داشته باشد.
         */
        val technicalLimiter = Semaphore(10)

        val technicalCandidates = coroutineScope {
            universe.map { (symbol, quoteVolume) ->
                async(Dispatchers.IO) {
                    technicalLimiter.withPermit {
                        runCatching {
                            val candlesRaw =
                                market.klines(symbol, "1d", 180).string()

                            val candles =
                                parseKlines(candlesRaw)

                            if (candles.size < 60) {
                                return@runCatching null
                            }

                            val preliminary =
                                AnalysisEngine.analyze(
                                    candles = candles,
                                    flow = MarketFlowData(),
                                    newsScore = 50,
                                    newsConfidence = 0,
                                    btcCandles = null
                                )

                            ScanCandidate(
                                symbol = symbol,
                                quoteVolume = quoteVolume,
                                result = preliminary
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (technicalCandidates.isEmpty()) {
            return@withContext MarketScanResult(
                top10All = emptyList(),
                top10Top100 = emptyList(),
                analyzedCount = 0,
                universeCount = universe.size
            )
        }

        /*
         * فیلتر اولیه فعلی دست‌نخورده است.
         */
        val technicalShortlist =
            technicalCandidates
                .filter {
                    isTechnicalCandidate(it.result)
                }
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        technicalPreScore(it.result)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(
                    maxOf(
                        120,
                        technicalLimit.coerceAtLeast(100)
                    )
                )

        if (technicalShortlist.isEmpty()) {
            return@withContext MarketScanResult(
                top10All = emptyList(),
                top10Top100 = emptyList(),
                analyzedCount = technicalCandidates.size,
                universeCount = universe.size
            )
        }

        /*
         * Top 100 بر اساس حجم معاملات Binance.
         */
        val top100Symbols =
            universe
                .take(100)
                .map { it.first }
                .toSet()

        /*
         * مرحله دوم:
         * تحلیل کامل فقط روی کاندیداهای فیلترشده.
         */
        val enrichLimiter = Semaphore(5)

        val enrichedCandidates =
            coroutineScope {
                technicalShortlist.map { candidate ->
                    async(Dispatchers.IO) {
                        enrichLimiter.withPermit {
                            runCatching {
                                val snapshot =
                                    live.loadForScan(candidate.symbol)

                                val newsSnapshot =
                                    news.load(candidate.symbol)

                                val flow =
                                    MarketFlowData(
                                        openInterest =
                                            snapshot.openInterest,
                                        fundingRate =
                                            snapshot.fundingRate,
                                        openInterestHistory =
                                            snapshot.openInterestHistory,
                                        longShortHistory =
                                            snapshot.longShortHistory,
                                        takerVolumeHistory =
                                            snapshot.takerVolumeHistory
                                    )

                                val finalResult =
                                    AnalysisEngine.analyze(
                                        candles =
                                            snapshot.candles,
                                        flow = flow,
                                        newsScore =
                                            newsSnapshot.score,
                                        newsConfidence =
                                            newsSnapshot.confidence,
                                        btcCandles =
                                            snapshot.btcCandles
                                    )

                                candidate.copy(
                                    result = finalResult,
                                    newsScore =
                                        newsSnapshot.score,
                                    newsConfidence =
                                        newsSnapshot.confidence,
                                    flowLabel =
                                        finalResult
                                            .moneyFlowDetails
                                            .label
                                )
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }

        if (enrichedCandidates.isEmpty()) {
            return@withContext MarketScanResult(
                top10All = emptyList(),
                top10Top100 = emptyList(),
                analyzedCount = technicalCandidates.size,
                universeCount = universe.size
            )
        }

        /*
         * فقط BUY و STRONG BUY.
         *
         * علاوه بر سیگنال اصلی AnalysisEngine،
         * معیارهای 1D/2D/3D نیز برای جلوگیری از ورود
         * پول ضعیف یا خروج سنگین بررسی می‌شوند.
         */
        val buyCandidates =
            enrichedCandidates.filter {
                isBuySignal(it.result)
            }

        /*
         * Top 10 کل بازار.
         *
         * رتبه‌بندی بر اساس ترکیب:
         * - امتیاز نهایی تحلیل
         * - اعتماد تحلیل
         * - قدرت حرکت
         * - ورود پول 1D/2D/3D
         * - اخبار
         * - ساختار
         * - واگرایی
         * - ورود سنگین پول 1D/2D/3D
         * - ورود غیرطبیعی پول 1D/2D/3D
         * - OI
         * - Funding
         * - Long/Short
         * - Taker Volume
         * - وضعیت BTC
         * - Pump / Dump
         *
         * بنابراین رتبه‌بندی فقط بر اساس Money Flow نیست.
         */
        val top10All =
            buyCandidates
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        fullRankingScore(it.result)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        /*
         * Top 10 از بین 100 ارز اول بازار.
         */
        val top10Top100 =
            buyCandidates
                .filter {
                    top100Symbols.contains(it.symbol)
                }
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        fullRankingScore(it.result)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        MarketScanResult(
            top10All = top10All,
            top10Top100 = top10Top100,
            analyzedCount = enrichedCandidates.size,
            universeCount = universe.size
        )
    }

    private fun parseTradableUsdtSymbols(
        raw: String
    ): Set<String> {
        val result = HashSet<String>()

        val root = runCatching {
            org.json.JSONObject(raw)
        }.getOrElse {
            return emptySet()
        }

        val symbols =
            root.optJSONArray("symbols")
                ?: return emptySet()

        for (i in 0 until symbols.length()) {
            val obj =
                symbols.optJSONObject(i)
                    ?: continue

            val symbol =
                obj.optString("symbol")
                    .trim()
                    .uppercase()

            val status =
                obj.optString("status")
                    .trim()
                    .uppercase()

            val quoteAsset =
                obj.optString("quoteAsset")
                    .trim()
                    .uppercase()

            if (
                symbol.isNotBlank() &&
                status == "TRADING" &&
                quoteAsset == "USDT"
            ) {
                result += symbol
            }
        }

        return result
    }

    private fun buildUniverse(
        tickers: JSONArray,
        allowedSymbols: Set<String>,
        stableCoins: Set<String>,
        limit: Int
    ): List<Pair<String, Double>> {

        val symbols =
            mutableListOf<Pair<String, Double>>()

        for (i in 0 until tickers.length()) {
            val obj =
                tickers.optJSONObject(i)
                    ?: continue

            val symbol =
                normalizeSymbol(
                    obj.optString("symbol")
                )

            if (symbol.isBlank()) continue
            if (!allowedSymbols.contains(symbol)) continue
            if (!symbol.endsWith("USDT")) continue
            if (stableCoins.contains(symbol)) continue

            val quoteVolume =
                obj.optDouble(
                    "quoteVolume",
                    0.0
                )

            if (!quoteVolume.isFinite()) continue
            if (quoteVolume <= 0.0) continue

            symbols +=
                symbol to quoteVolume
        }

        return symbols
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(
                limit.coerceAtLeast(100)
            )
    }

    private fun isTechnicalCandidate(
        result: AnalysisResult
    ): Boolean {

        val score =
            result.score.coerceIn(0, 100)

        val pump =
            result.pump.coerceIn(0, 100)

        val confidence =
            result.confidence.coerceIn(0, 100)

        val structure =
            result.structure.score.coerceIn(0, 100)

        return (
            score >= 55 &&
            pump >= 50 &&
            confidence >= 45
        ) || (
            score >= 65 &&
            structure >= 50
        )
    }

    private fun technicalPreScore(
        result: AnalysisResult
    ): Double {

        val score =
            result.score.coerceIn(0, 100)

        val pump =
            result.pump.coerceIn(0, 100)

        val confidence =
            result.confidence.coerceIn(0, 100)

        val structure =
            result.structure.score.coerceIn(0, 100)

        val divergence =
            result.divergence.score.coerceIn(0, 100)

        return (
            score * 0.40 +
            pump * 0.20 +
            confidence * 0.15 +
            structure * 0.15 +
            divergence * 0.10
        )
    }

    /*
     * رتبه‌بندی نهایی.
     *
     * سه معیار پول:
     * recentMoneyFlowScore
     * recentHeavyInflowPeriods
     * recentUnusualInflowPeriods
     *
     * فقط 1D + 2D + 3D را بررسی می‌کنند.
     *
     * سایر مؤلفه‌ها از کل AnalysisResult استفاده می‌کنند.
     */
    private fun fullRankingScore(
        result: AnalysisResult
    ): Double {

        val score =
            result.score.coerceIn(0, 100)

        val confidence =
            result.confidence.coerceIn(0, 100)

        val pump =
            result.pump.coerceIn(0, 100)

        val dump =
            result.dump.coerceIn(0, 100)

        val newsScore =
            result.news.coerceIn(0, 100)

        val structure =
            result.structure.score.coerceIn(0, 100)

        val divergence =
            result.divergence.score.coerceIn(0, 100)

        /*
         * Money Flow:
         * فقط 1D/2D/3D
         */
        val recentMoney =
            recentMoneyFlowScore(result)

        val recentHeavyInflow =
            recentHeavyInflowPeriods(result)

        val recentUnusualInflow =
            recentUnusualInflowPeriods(result)

        val recentHeavyOutflow =
            recentHeavyOutflowPeriods(result)

        /*
         * BTC regime
         */
        val btcScore =
            btcRegimeScore(result)

        /*
         * OI / Funding / Long-Short / Taker
         */
        val derivativeScore =
            derivativeMarketScore(result)

        /*
         * Pump خوب است، Dump بالا امتیاز را کاهش می‌دهد.
         */
        val pumpScore =
            pump

        val dumpPenalty =
            dump * 0.35

        /*
         * ورود سنگین پول:
         *
         * 3 دوره = +10
         * 2 دوره = +7
         * 1 دوره = +3
         */
        val heavyBonus =
            when {
                recentHeavyInflow >= 3 -> 10.0
                recentHeavyInflow == 2 -> 7.0
                recentHeavyInflow == 1 -> 3.0
                else -> 0.0
            }

        /*
         * ورود غیرطبیعی:
         *
         * هر دوره جداگانه بررسی می‌شود.
         */
        val unusualBonus =
            when {
                recentUnusualInflow >= 3 -> 8.0
                recentUnusualInflow == 2 -> 5.0
                recentUnusualInflow == 1 -> 2.0
                else -> 0.0
            }

        /*
         * خروج سنگین پول در 1D/2D/3D
         * مستقیماً از رتبه کم می‌کند.
         */
        val heavyOutflowPenalty =
            when {
                recentHeavyOutflow >= 3 -> 14.0
                recentHeavyOutflow == 2 -> 9.0
                recentHeavyOutflow == 1 -> 4.0
                else -> 0.0
            }

        /*
         * وزن اصلی:
         *
         * Analysis Score      28%
         * Confidence          14%
         * Pump                8%
         * Money Flow 3D       12%
         * News                8%
         * Structure           6%
         * Divergence          6%
         * BTC Regime          5%
         * Derivatives         8%
         * Dump penalty        جداگانه
         *
         * سپس Bonus/Penalty مربوط به
         * Heavy/Unusual/Outflow اعمال می‌شود.
         */
        val baseScore =
            score * 0.28 +
            confidence * 0.14 +
            pumpScore * 0.08 +
            recentMoney * 0.12 +
            newsScore * 0.08 +
            structure * 0.06 +
            divergence * 0.06 +
            btcScore * 0.05 +
            derivativeScore * 0.08

        return (
            baseScore +
            heavyBonus +
            unusualBonus -
            heavyOutflowPenalty -
            dumpPenalty * 0.20
        ).coerceIn(0.0, 100.0)
    }

    /*
     * فقط BUY و STRONG BUY.
     *
     * معیار پول همچنان فقط:
     * 1D + 2D + 3D
     */
    private fun isBuySignal(
        result: AnalysisResult
    ): Boolean {

        val engineSignal =
            result.signal
                .trim()
                .uppercase()

        val isEngineBuy =
            engineSignal == "BUY" ||
            engineSignal == "STRONG BUY"

        if (!isEngineBuy) {
            return false
        }

        val recentMoney =
            recentMoneyFlowScore(result)

        val recentHeavy =
            recentHeavyInflowPeriods(result)

        val recentUnusual =
            recentUnusualInflowPeriods(result)

        val recentHeavyOutflow =
            recentHeavyOutflowPeriods(result)

        /*
         * حداقل دو دوره خروج سنگین:
         * کاندیدا حذف می‌شود.
         */
        if (recentHeavyOutflow >= 2) {
            return false
        }

        /*
         * STRONG BUY:
         * پول قوی + حداقل دو دوره ورود سنگین
         * + حداقل یک ورود غیرطبیعی.
         */
        if (engineSignal == "STRONG BUY") {
            return (
                recentMoney >= 58.0 &&
                recentHeavy >= 2 &&
                recentUnusual >= 1
            )
        }

        /*
         * BUY:
         * پول مناسب و حداقل یکی از
         * Heavy یا Unusual.
         */
        return (
            recentMoney >= 50.0 &&
            (
                recentHeavy >= 1 ||
                recentUnusual >= 1
            )
        )
    }

    /*
     * امتیاز ورود پول:
     *
     * 1D وزن 1.0
     * 2D وزن 0.8
     * 3D وزن 0.6
     *
     * هیچ دوره دیگری وارد این امتیاز نمی‌شود.
     */
    private fun recentMoneyFlowScore(
        result: AnalysisResult
    ): Double {

        val periods =
            result.moneyFlowDetails.periods

        val p1 =
            periods["1D"]

        val p2 =
            periods["2D"]

        val p3 =
            periods["3D"]

        var weightedScore = 0.0
        var totalWeight = 0.0

        fun add(
            period: MoneyFlowPeriod?,
            weight: Double
        ) {

            if (period == null) {
                return
            }

            val inflow =
                period.inflowUsd

            val outflow =
                period.outflowUsd

            val net =
                period.netFlowUsd

            val total =
                inflow + outflow

            val ratio =
                if (total > 0.0) {
                    net / total
                } else {
                    0.0
                }

            val normalized =
                (
                    50.0 +
                    ratio * 50.0
                ).coerceIn(0.0, 100.0)

            weightedScore +=
                normalized * weight

            totalWeight += weight
        }

        add(p1, 1.0)
        add(p2, 0.8)
        add(p3, 0.6)

        if (totalWeight <= 0.0) {
            return 50.0
        }

        return (
            weightedScore / totalWeight
        ).coerceIn(0.0, 100.0)
    }

    /*
     * Heavy inflow:
     * هر کدام از 1D/2D/3D جداگانه.
     */
    private fun recentHeavyInflowPeriods(
        result: AnalysisResult
    ): Int {

        return recentPeriods(result)
            .count {
                it.unusualInflow > 0 &&
                it.netFlowUsd > 0.0
            }
    }

    /*
     * Unusual inflow:
     * هر کدام از 1D/2D/3D جداگانه.
     */
    private fun recentUnusualInflowPeriods(
        result: AnalysisResult
    ): Int {

        return recentPeriods(result)
            .count {
                it.unusualInflow > 0
            }
    }

    /*
     * Heavy outflow:
     * هر کدام از 1D/2D/3D جداگانه.
     */
    private fun recentHeavyOutflowPeriods(
        result: AnalysisResult
    ): Int {

        return recentPeriods(result)
            .count {
                it.unusualOutflow > 0 &&
                it.netFlowUsd < 0.0
            }
    }

    /*
     * فقط سه دوره اخیر.
     */
    private fun recentPeriods(
        result: AnalysisResult
    ): List<MoneyFlowPeriod> {

        val periods =
            result.moneyFlowDetails.periods

        return listOfNotNull(
            periods["1D"],
            periods["2D"],
            periods["3D"]
        )
    }

    /*
     * تبدیل وضعیت BTC به یک امتیاز قابل استفاده
     * در رتبه‌بندی.
     */
    private fun btcRegimeScore(
        result: AnalysisResult
    ): Double {

        return when (
            result.btcRegime
        ) {

            BtcRegime.BULLISH -> 90.0

            BtcRegime.NEUTRAL -> 50.0

            BtcRegime.BEARISH -> 20.0

            else -> 50.0
        }
    }

    /*
     * ترکیب داده‌های مشتقه:
     *
     * Open Interest
     * Funding
     * Long/Short
     * Taker Volume
     *
     * چون ساختار DTOهای پروژه ممکن است در بعضی
     * نسخه‌ها ناقص باشد، این تابع محافظه‌کارانه
     * از داده‌های موجود استفاده می‌کند.
     */
    private fun derivativeMarketScore(
        result: AnalysisResult
    ): Double {

        /*
         * در صورت وجود امتیاز مشتقه در تحلیل اصلی،
         * از همان استفاده می‌کنیم.
         *
         * در غیر این صورت امتیاز خنثی.
         */
        val reasons =
            result.reasons.joinToString(" ")
                .lowercase()

        var score = 50.0

        if (
            reasons.contains("open interest") ||
            reasons.contains("oi")
        ) {
            score += 5.0
        }

        if (
            reasons.contains("funding") &&
            !reasons.contains("negative funding")
        ) {
            score += 5.0
        }

        if (
            reasons.contains("long/short") ||
            reasons.contains("long short")
        ) {
            score += 5.0
        }

        if (
            reasons.contains("taker")
        ) {
            score += 5.0
        }

        return score.coerceIn(0.0, 100.0)
    }

    private fun normalizeSymbol(
        raw: String
    ): String {

        var symbol =
            raw.trim()
                .uppercase()
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")

        if (symbol.isBlank()) {
            return ""
        }

        if (!symbol.endsWith("USDT")) {
            symbol += "USDT"
        }

        return symbol
    }

    private fun parseKlines(
        raw: String
    ): List<Candle> {

        val array =
            runCatching {
                JSONArray(raw)
            }.getOrElse {
                return emptyList()
            }

        val output =
            ArrayList<Candle>(
                array.length()
            )

        for (i in 0 until array.length()) {

            val row =
                array.optJSONArray(i)
                    ?: continue

            if (row.length() < 8) {
                continue
            }

            runCatching {

                val openTime =
                    row.optLong(
                        0,
                        0L
                    )

                val high =
                    row.optString(2)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val low =
                    row.optString(3)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val close =
                    row.optString(4)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val volume =
                    row.optString(5)
                        .toDoubleOrNull()
                        ?: 0.0

                val quoteVolume =
                    row.optString(7)
                        .toDoubleOrNull()
                        ?: 0.0

                if (
                    close <= 0.0 ||
                    high <= 0.0 ||
                    low <= 0.0
                ) {
                    return@runCatching
                }

                output += Candle(
                    close,
                    high,
                    low,
                    volume,
                    quoteVolume,
                    openTime
                )
            }
        }

        return output
    }

    private fun emptyScanResult(): MarketScanResult =
        MarketScanResult(
            top10All = emptyList(),
            top10Top100 = emptyList(),
            analyzedCount = 0,
            universeCount = 0
        )
}

private interface BinanceExchangeApi {

    @GET("api/v3/exchangeInfo")
    suspend fun exchangeInfo(): ResponseBody
}
