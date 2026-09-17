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

        val symbols = mutableListOf<Pair<String, Double>>()

        for (i in 0 until tickers.length()) {

            val obj =
                tickers.optJSONObject(i)
                    ?: continue

            val symbol =
                normalizeSymbol(
                    obj.optString("symbol")
                )

            if (symbol.isBlank()) continue
            if (!symbol.endsWith("USDT")) continue
            if (stableCoins.contains(symbol)) continue

            val quoteVolume =
                obj.optDouble(
                    "quoteVolume",
                    0.0
                )

            if (!quoteVolume.isFinite()) continue
            if (quoteVolume <= 0.0) continue

            symbols += symbol to quoteVolume
        }

        val universe =
            symbols
                .distinctBy { it.first }
                .sortedByDescending { it.second }
                .take(universeLimit)

        if (universe.isEmpty()) {
            return@withContext emptyScanResult()
        }

        /*
         * مرحله اول:
         * تحلیل تکنیکال سریع برای کل Universe
         */
        val technicalLimiter = Semaphore(8)

        val technicalCandidates =
            coroutineScope {

                universe.map { (symbol, quoteVolume) ->

                    async(Dispatchers.IO) {

                        technicalLimiter.withPermit {

                            runCatching {

                                val candlesRaw =
                                    market.klines(
                                        symbol,
                                        "1d",
                                        180
                                    ).string()

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
                }.awaitAll()
                    .filterNotNull()
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
         * رتبه‌های 1 تا 100 بر اساس حجم معاملات بازار
         */
        val top100Symbols =
            universe
                .take(100)
                .map { it.first }
                .toSet()

        /*
         * برای مرحله Enrichment، ارزهای قوی‌تر را انتخاب می‌کنیم.
         *
         * نکته:
         * هنوز BUY/STRONG BUY نهایی نیست؛ چون برای تصمیم نهایی
         * باید Money Flow + News + BTC Regime دریافت شود.
         */
        val rankedTechnical =
            technicalCandidates
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        rankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )

        val technicalTop100 =
            rankedTechnical
                .filter {
                    top100Symbols.contains(it.symbol)
                }
                .take(100)

        val technicalTopOverall =
            rankedTechnical
                .take(
                    technicalLimit.coerceAtLeast(100)
                )

        /*
         * برای اینکه ارزهای BUY بالقوه از کل بازار از دست نروند،
         * مجموعه Enrichment نسبتاً بزرگ نگه داشته می‌شود.
         */
        val enrichCandidates =
            (
                technicalTop100 +
                    technicalTopOverall
                )
                .distinctBy {
                    it.symbol
                }
                .take(
                    maxOf(
                        enrichLimit,
                        technicalTop100.size,
                        technicalLimit.coerceAtMost(
                            technicalCandidates.size
                        )
                    )
                )

        val enrichLimiter = Semaphore(6)

        /*
         * مرحله دوم:
         * دریافت Money Flow + News + BTC Regime
         */
        val enrichedCandidates =
            coroutineScope {

                enrichCandidates.map { candidate ->

                    async(Dispatchers.IO) {

                        enrichLimiter.withPermit {

                            runCatching {

                                val snapshot =
                                    live.loadForScan(
                                        candidate.symbol
                                    )

                                val newsSnapshot =
                                    news.load(
                                        candidate.symbol
                                    )

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

                            }.getOrElse {
                                candidate
                            }
                        }
                    }
                }.awaitAll()
            }

        val enrichedMap =
            enrichedCandidates.associateBy {
                it.symbol
            }

        /*
         * نتیجه نهایی:
         * ارزهای Enrichment شده جایگزین نسخه تکنیکال اولیه می‌شوند.
         */
        val finalCandidates =
            technicalCandidates.map { candidate ->
                enrichedMap[candidate.symbol]
                    ?: candidate
            }

        /*
         * فقط سیگنال‌های خرید
         *
         * مهم:
         * finalSignal همان منطق MainActivity است.
         *
         * فقط:
         * 🟢 پیشنهاد خرید
         * 🟢 پیشنهاد خرید قوی
         *
         * مجاز هستند.
         */
        val buyCandidates =
            finalCandidates
                .filter {
                    isBuySignal(it.result)
                }

        /*
         * 10 ارز برتر از کل بازار
         */
        val top10All =
            buyCandidates
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        buyRankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        /*
         * 10 ارز برتر فقط از رتبه‌های 1 تا 100
         */
        val top10Top100 =
            buyCandidates
                .filter {
                    top100Symbols.contains(it.symbol)
                }
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        buyRankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        MarketScanResult(
            top10All = top10All,
            top10Top100 = top10Top100,
            analyzedCount = finalCandidates.size,
            universeCount = universe.size
        )
    }

    private fun emptyScanResult(): MarketScanResult {

        return MarketScanResult(
            top10All = emptyList(),
            top10Top100 = emptyList(),
            analyzedCount = 0,
            universeCount = 0
        )
    }

    /*
     * رتبه‌بندی اولیه قبل از Enrichment
     */
    private fun rankingScore(
        candidate: ScanCandidate
    ): Double {

        val result =
            candidate.result

        val directional =
            maxOf(
                result.pump,
                result.dump
            )
                .coerceIn(0, 100)

        val confidence =
            result.confidence
                .coerceIn(0, 100)

        val scoreDistance =
            kotlin.math.abs(
                result.score - 50
            )

        val moneyFlowScore =
            result.moneyFlowDetails.score
                .coerceIn(0, 100)

        val flowStrength =
            kotlin.math.abs(
                moneyFlowScore - 50
            )

        return (
            scoreDistance * 0.40 +
                directional * 0.20 +
                confidence * 0.20 +
                flowStrength * 0.20
            )
    }

    /*
     * امتیاز نهایی برای انتخاب بین ارزهای خرید.
     *
     * برخلاف رتبه‌بندی قبلی، اینجا فقط قدرت صعودی
     * اهمیت دارد و قدرت Dump باعث بالا آمدن ارز نمی‌شود.
     */
    private fun buyRankingScore(
        candidate: ScanCandidate
    ): Double {

        val result =
            candidate.result

        val score =
            result.score
                .coerceIn(0, 100)

        val confidence =
            result.confidence
                .coerceIn(0, 100)

        val pump =
            result.pump
                .coerceIn(0, 100)

        val moneyFlow =
            result.moneyFlow
                .coerceIn(0, 100)

        val news =
            result.news
                .coerceIn(0, 100)

        val structure =
            result.structure.score
                .coerceIn(0, 100)

        val divergence =
            result.divergence.score
                .coerceIn(0, 100)

        /*
         * قدرت ورود پول
         */
        val moneyFlowStrength =
            when {

                moneyFlow >= 80 ->
                    12.0

                moneyFlow >= 70 ->
                    8.0

                moneyFlow >= 60 ->
                    4.0

                else ->
                    0.0
            }

        /*
         * ورود سنگین پول
         */
        val heavyInflowBonus =
            if (
                isHeavyInflow(
                    result.moneyFlowDetails.label
                )
            ) {
                10.0
            } else {
                0.0
            }

        /*
         * ورود غیرطبیعی پول
         *
         * اگر در یکی از دوره‌های Money Flow
         * unusualInflow ثبت شده باشد، امتیاز اضافه می‌شود.
         */
        val unusualInflowCount =
            result.moneyFlowDetails.periods.values.count {
                it.unusualInflow > 0
            }

        val unusualInflowBonus =
            when {

                unusualInflowCount >= 3 ->
                    12.0

                unusualInflowCount == 2 ->
                    8.0

                unusualInflowCount == 1 ->
                    4.0

                else ->
                    0.0
            }

        /*
         * ورود سنگین در چند دوره
         */
        val heavyInflowPeriods =
            result.moneyFlowDetails.periods.values.count {
                it.unusualInflow > 0 &&
                    it.netFlowUsd > 0.0
            }

        val multiPeriodFlowBonus =
            when {

                heavyInflowPeriods >= 3 ->
                    8.0

                heavyInflowPeriods == 2 ->
                    5.0

                heavyInflowPeriods == 1 ->
                    2.0

                else ->
                    0.0
            }

        /*
         * اگر خروج سنگین وجود داشته باشد، از رتبه کم می‌کنیم.
         */
        val heavyOutflowPenalty =
            if (
                isHeavyOutflow(
                    result.moneyFlowDetails.label
                )
            ) {
                12.0
            } else {
                0.0
            }

        /*
         * اگر Score بالا ولی Money Flow ضعیف باشد،
         * از امتیاز نهایی کمی کم می‌کنیم.
         */
        val flowConflictPenalty =
            if (
                score >= 70 &&
                    moneyFlow < 45
            ) {
                6.0
            } else {
                0.0
            }

        return (
            score * 0.32 +
                confidence * 0.18 +
                pump * 0.18 +
                moneyFlow * 0.12 +
                news * 0.05 +
                structure * 0.05 +
                divergence * 0.03 +
                moneyFlowStrength +
                heavyInflowBonus +
                unusualInflowBonus +
                multiPeriodFlowBonus -
                heavyOutflowPenalty -
                flowConflictPenalty
            )
    }

    /*
     * فقط BUY و STRONG BUY
     */
    private fun isBuySignal(
        result: AnalysisResult
    ): Boolean {

        val score =
            result.score
                .coerceIn(0, 100)

        val confidence =
            result.confidence
                .coerceIn(0, 100)

        val pump =
            result.pump
                .coerceIn(0, 100)

        val heavyInflow =
            isHeavyInflow(
                result.moneyFlowDetails.label
            )

        val heavyOutflow =
            isHeavyOutflow(
                result.moneyFlowDetails.label
            )

        /*
         * پیشنهاد خرید قوی
         */
        val strongBuy =
            score >= 82 &&
                confidence >= 75 &&
                pump >= 75 &&
                heavyInflow &&
                result.news >= 45 &&
                !heavyOutflow

        if (strongBuy) {
            return true
        }

        /*
         * پیشنهاد خرید
         */
        val buy =
            score >= 70 &&
                confidence >= 60 &&
                pump >= 65 &&
                !heavyOutflow

        return buy
    }

    private fun isHeavyInflow(
        label: String
    ): Boolean {

        val normalized =
            label
                .trim()
                .uppercase()

        return normalized.contains(
            "HEAVY INFLOW"
        ) ||
            normalized.contains(
                "INFLOW HEAVY"
            ) ||
            label.contains(
                "ورود سنگین"
            ) ||
            label.contains(
                "ورود غیرعادی"
            )
    }

    private fun isHeavyOutflow(
        label: String
    ): Boolean {

        val normalized =
            label
                .trim()
                .uppercase()

        return normalized.contains(
            "HEAVY OUTFLOW"
        ) ||
            normalized.contains(
                "OUTFLOW HEAVY"
            ) ||
            label.contains(
                "خروج سنگین"
            ) ||
            label.contains(
                "خروج غیرعادی"
            )
    }

    private fun normalizeSymbol(
        raw: String
    ): String {

        var symbol =
            raw
                .trim()
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
                    close = close,
                    high = high,
                    low = low,
                    volume = volume,
                    quoteVolume = quoteVolume,
                    openTime = openTime
                )
            }
        }

        return output
    }
}

نسخه بعدی که باید اصلاح کنیم "MainActivity.kt" است تا جدول دقیقاً ستون‌های "پیشنهاد | امتیاز تحلیل | ورود پول | ورود سنگین پول | ورود غیرطبیعی پول" را نشان بدهد.
