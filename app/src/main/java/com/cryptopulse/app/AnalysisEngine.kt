package com.cryptopulse.app

import kotlin.math.*

data class Candle(
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val quoteVolume: Double = 0.0,
    val openTime: Long = 0L
)

data class MoneyFlowPeriod(
    val key: String,
    val title: String,
    val inflowUsd: Double,
    val outflowUsd: Double,
    val netFlowUsd: Double,
    val inflowChangePct: Double,
    val outflowChangePct: Double,
    val netChangePct: Double,
    val unusualInflow: Int,
    val unusualOutflow: Int,
    val confidence: Int,
    val status: String,
    val reasons: List<String> = emptyList()
)

data class MoneyFlowResult(
    val score: Int,
    val confidence: Int,
    val label: String,
    val reasons: List<String>,
    val periods: Map<String, MoneyFlowPeriod> = emptyMap()
)

data class TradePlan(
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val riskReward: Double
)

data class StructureResult(
    val score: Int,
    val label: String,
    val breakout: Boolean,
    val breakdown: Boolean,
    val support: Double,
    val resistance: Double,
    val reasons: List<String>
)

data class DivergenceResult(
    val score: Int,
    val label: String,
    val bullish: Boolean,
    val bearish: Boolean,
    val reasons: List<String>
)

data class BtcRegime(
    val score: Int,
    val label: String,
    val reasons: List<String>
)

data class AnalysisWeights(
    val technical: Double = 0.24,
    val moneyFlow: Double = 0.22,
    val timeframe: Double = 0.18,
    val structure: Double = 0.10,
    val divergence: Double = 0.06,
    val news: Double = 0.12,
    val btc: Double = 0.08
) {
    fun normalized(): AnalysisWeights {
        val sum = technical + moneyFlow + timeframe +
                structure + divergence + news + btc

        if (sum <= 0) return AnalysisWeights()

        return AnalysisWeights(
            technical / sum,
            moneyFlow / sum,
            timeframe / sum,
            structure / sum,
            divergence / sum,
            news / sum,
            btc / sum
        )
    }
}

data class AnalysisResult(
    val score: Int,
    val pump: Int,
    val dump: Int,
    val confidence: Int,
    val signal: String,
    val reasons: List<String>,
    val moneyFlow: Int = 50,
    val trend: Int = 50,
    val news: Int = 50,
    val timeframeScores: Map<String, Int> = emptyMap(),
    val moneyFlowDetails: MoneyFlowResult =
        MoneyFlowResult(
            50,
            0,
            "خنثی",
            emptyList()
        ),
    val structure: StructureResult =
        StructureResult(
            50,
            "NEUTRAL",
            false,
            false,
            0.0,
            0.0,
            emptyList()
        ),
    val divergence: DivergenceResult =
        DivergenceResult(
            50,
            "NONE",
            false,
            false,
            emptyList()
        ),
    val btcRegime: BtcRegime =
        BtcRegime(
            50,
            "NEUTRAL",
            emptyList()
        ),
    val tradePlan: TradePlan? = null
)

data class MarketFlowData(
    val openInterest: Double? = null,
    val fundingRate: Double? = null,
    val openInterestHistory: List<OpenInterestHistDto> = emptyList(),
    val longShortHistory: List<LongShortDto> = emptyList(),
    val takerVolumeHistory: List<TakerVolumeDto> = emptyList()
)

object AnalysisEngine {

    private data class PeriodDefinition(
        val key: String,
        val title: String,
        val days: Int
    )

    private val moneyFlowPeriods = listOf(
        PeriodDefinition("1D", "1 روزه", 1),
        PeriodDefinition("2D", "2 روزه", 2),
        PeriodDefinition("3D", "3 روزه", 3),
        PeriodDefinition("4D", "4 روزه", 4),
        PeriodDefinition("1W", "1 هفته", 7),
        PeriodDefinition("1M", "1 ماه", 30),
        PeriodDefinition("3M", "3 ماه", 90),
        PeriodDefinition("6M", "6 ماه", 180)
    )

    fun analyze(
        candles: List<Candle>,
        flow: MarketFlowData = MarketFlowData(),
        newsScore: Int = 50,
        newsConfidence: Int = 0,
        btcCandles: List<Candle>? = null,
        weights: AnalysisWeights = AnalysisWeights()
    ): AnalysisResult {

        if (candles.size < 60) {
            return AnalysisResult(
                0,
                0,
                0,
                0,
                "WAIT",
                listOf("داده کافی نیست")
            )
        }

        val frames = mapOf(
            "1D" to 1,
            "2D" to 2,
            "3D" to 3,
            "4D" to 4,
            "1W" to 7,
            "1M" to 30
        )

        val tf = frames.mapValues { (_, days) ->
            scoreFrame(resample(candles, days))
        }

        val weightedTf = (
                tf["1M"]!! * 0.25 +
                        tf["1W"]!! * 0.25 +
                        tf["4D"]!! * 0.15 +
                        tf["3D"]!! * 0.10 +
                        tf["2D"]!! * 0.10 +
                        tf["1D"]!! * 0.15
                ).roundToInt()

        val base = scoreCore(candles)

        val money = scoreMoneyFlow(
            candles,
            flow
        )

        val structure =
            analyzeStructure(candles)

        val divergence =
            analyzeDivergence(candles)

        val btc =
            analyzeBtcRegime(
                btcCandles ?: candles
            )

        val w = weights.normalized()

        val score = (
                base.tech * w.technical +
                        money.score * w.moneyFlow +
                        weightedTf * w.timeframe +
                        structure.score * w.structure +
                        divergence.score * w.divergence +
                        newsScore * w.news +
                        btc.score * w.btc
                ).roundToInt().coerceIn(0, 100)

        val pump = (
                (score - 50) * 1.35 + 50
                ).roundToInt().coerceIn(1, 98)

        val dump = (
                (50 - score) * 1.35 + 50
                ).roundToInt().coerceIn(1, 98)

        val agreement =
            tf.values
                .map { it >= 60 }
                .let {
                    if (
                        it.count { b -> b } >= 5 ||
                        it.count { b -> !b } >= 5
                    ) 10 else 0
                }

        val confidence = (
                50 +
                        min(20, candles.size / 30) +
                        if (flow.openInterest != null) 4 else 0 +
                        if (flow.fundingRate != null) 4 else 0 +
                        min(8, money.confidence / 12) +
                        if (newsConfidence > 0) 10 else 0 +
                        agreement
                ).coerceIn(0, 98)

        val signal = when {
            confidence < 65 -> "WAIT"
            score >= 76 && pump >= 70 -> "BUY"
            score <= 34 && dump >= 70 -> "SELL"
            else -> "WAIT"
        }

        val reasons = base.reasons.toMutableList()

        reasons += money.reasons
        reasons += structure.reasons
        reasons += divergence.reasons
        reasons += btc.reasons

        reasons += "تایم‌فریم‌های سنگین‌تر وزن بیشتری دارند"
        reasons += "News score: $newsScore/100"

        val plan =
            buildTradePlan(
                candles,
                structure,
                signal
            )

        return AnalysisResult(
            score,
            pump,
            dump,
            confidence,
            signal,
            reasons,
            money.score,
            weightedTf,
            newsScore,
            tf,
            money,
            structure,
            divergence,
            btc,
            plan
        )
    }

    private data class Core(
        val tech: Int,
        val reasons: List<String>
    )

    private fun scoreCore(
        candles: List<Candle>
    ): Core {

        val closes =
            candles.map { it.close }

        val last =
            closes.last()

        val reasons =
            mutableListOf<String>()

        fun ema(p: Int): Double {

            val n =
                minOf(p, closes.size)

            val k =
                2.0 / (n + 1)

            var e =
                closes.take(n).average()

            for (i in n until closes.size) {
                e =
                    closes[i] * k +
                            e * (1 - k)
            }

            return e
        }

        fun rsi(
            p: Int = 14
        ): Double {

            var g = 0.0
            var l = 0.0

            for (
                i in closes.size - p until closes.size
            ) {

                val d =
                    closes[i] - closes[i - 1]

                if (d > 0) {
                    g += d
                } else {
                    l -= d
                }
            }

            return if (l == 0.0) {
                100.0
            } else {
                100 - 100 /
                        (1 + (g / p) / (l / p))
            }
        }

        val e20 = ema(20)
        val e50 = ema(50)
        val e100 = ema(100)
        val e200 = ema(200)

        val rr = rsi()

        var tech = 50

        if (last > e20) {
            tech += 8
            reasons += "قیمت بالای EMA20"
        } else {
            tech -= 8
        }

        if (e20 > e50) {
            tech += 7
            reasons += "EMA20 بالای EMA50"
        } else {
            tech -= 7
        }

        if (e50 > e100) {
            tech += 5
            reasons += "روند میان‌مدت صعودی"
        } else {
            tech -= 5
        }

        if (last > e200) {
            tech += 8
            reasons += "قیمت بالای EMA200"
        } else {
            tech -= 8
        }

        if (rr in 52.0..68.0) {
            tech += 7
            reasons += "RSI سالم"
        } else if (rr > 75) {
            tech -= 5
            reasons += "RSI بیش‌خرید"
        } else if (rr < 30) {
            tech += 2
            reasons += "RSI اشباع فروش"
        }

        return Core(
            tech.coerceIn(0, 100),
            reasons
        )
    }

    /**
     * موتور اصلی Money Flow
     *
     * نکته:
     * این مقدار "جریان پول تخمینی" است، نه جریان واقعی
     * ورود/خروج سرمایه از کیف پول یا صرافی.
     *
     * برای تخمین از:
     * - Quote Volume
     * - موقعیت Close داخل High/Low
     * - تغییر حجم
     * - OI
     * - Taker
     * - Funding
     * استفاده می‌شود.
     */
    private fun scoreMoneyFlow(
        candles: List<Candle>,
        flow: MarketFlowData
    ): MoneyFlowResult {

        val reasons =
            mutableListOf<String>()

        if (candles.size < 2) {
            return MoneyFlowResult(
                50,
                0,
                "خنثی",
                listOf("داده کافی برای جریان پول وجود ندارد")
            )
        }

        val periods =
            calculateMoneyFlowPeriods(candles)

        var score = 50
        var evidence = 0

        /*
         * امتیازدهی به بازه‌های مختلف
         *
         * کوتاه‌مدت + میان‌مدت + بلندمدت
         */
        val periodWeights =
            mapOf(
                "1D" to 0.08,
                "2D" to 0.08,
                "3D" to 0.09,
                "4D" to 0.09,
                "1W" to 0.14,
                "1M" to 0.18,
                "3M" to 0.16,
                "6M" to 0.18
            )

        var weightedNet = 0.0
        var weightTotal = 0.0

        periods.values.forEach { p ->

            val total =
                (
                        p.inflowUsd +
                                p.outflowUsd
                        ).coerceAtLeast(1.0)

            val direction =
                (
                        p.inflowUsd -
                                p.outflowUsd
                        ) / total

            val weight =
                periodWeights[p.key]
                    ?: 0.10

            weightedNet +=
                direction * weight

            weightTotal += weight

            if (p.unusualInflow >= 75) {
                reasons +=
                    "${p.title}: ورود پول غیرعادی و سنگین"
            }

            if (p.unusualOutflow >= 75) {
                reasons +=
                    "${p.title}: خروج پول غیرعادی و سنگین"
            }
        }

        val normalizedNet =
            if (weightTotal > 0) {
                weightedNet / weightTotal
            } else {
                0.0
            }

        score +=
            (
                    normalizedNet * 70
                    ).roundToInt().coerceIn(-25, 25)

        if (normalizedNet > 0.12) {
            evidence++
            reasons +=
                "در مجموع جریان پول به سمت ورود است"
        }

        if (normalizedNet < -0.12) {
            evidence++
            reasons +=
                "در مجموع جریان پول به سمت خروج است"
        }

        /*
         * حجم فعلی
         */
        val volumes =
            candles.map {
                effectiveQuoteVolume(it)
            }

        val recentVolumes =
            volumes.takeLast(31)

        val currentVolume =
            recentVolumes.lastOrNull()
                ?: 0.0

        val averageVolume =
            recentVolumes
                .dropLast(1)
                .average()
                .coerceAtLeast(1.0)

        val volumeRatio =
            currentVolume / averageVolume

        if (volumeRatio > 2.0) {
            evidence++
            reasons +=
                "ارزش معاملات روز جاری بیش از 2 برابر میانگین است"
        } else if (volumeRatio > 1.4) {
            evidence++
            reasons +=
                "ارزش معاملات افزایش یافته است"
        }

        /*
         * CMF
         */
        val cmfValue =
            cmf(candles.takeLast(20))

        score +=
            (
                    cmfValue * 25
                    ).roundToInt()
                .coerceIn(-15, 15)

        if (cmfValue > 0.15) {
            evidence++
            reasons +=
                "CMF مثبت؛ فشار خرید بیشتر است"
        } else if (cmfValue < -0.15) {
            evidence++
            reasons +=
                "CMF منفی؛ فشار فروش بیشتر است"
        }

        /*
         * OBV
         */
        val obv =
            obvSlope(
                candles.takeLast(20)
            )

        score +=
            (
                    obv * 15
                    ).roundToInt()
                .coerceIn(-10, 10)

        if (obv > 0.4) {
            evidence++
            reasons += "OBV صعودی است"
        } else if (obv < -0.4) {
            evidence++
            reasons += "OBV نزولی است"
        }

        /*
         * Open Interest
         */
        if (flow.openInterestHistory.size >= 2) {

            val first =
                flow.openInterestHistory
                    .first()
                    .sumOpenInterestValue
                    .toDoubleOrNull()
                    ?: 0.0

            val last =
                flow.openInterestHistory
                    .last()
                    .sumOpenInterestValue
                    .toDoubleOrNull()
                    ?: 0.0

            if (first > 0) {

                val delta =
                    last / first - 1

                if (delta > 0.05) {
                    score += 8
                    evidence++

                    reasons +=
                        "Open Interest بیش از 5% افزایش یافته است"
                } else if (delta < -0.05) {
                    score -= 6
                    evidence++

                    reasons +=
                        "Open Interest بیش از 5% کاهش یافته است"
                }
            }
        }

        /*
         * Taker Buy / Sell
         */
        if (flow.takerVolumeHistory.isNotEmpty()) {

            val buy =
                flow.takerVolumeHistory.sumOf {
                    it.takerBuyVolValue
                        .toDoubleOrNull()
                        ?: 0.0
                }

            val sell =
                flow.takerVolumeHistory.sumOf {
                    it.takerSellVolValue
                        .toDoubleOrNull()
                        ?: 0.0
                }

            val total =
                (buy + sell)
                    .coerceAtLeast(1e-9)

            val imbalance =
                (buy - sell) / total

            score +=
                (
                        imbalance * 30
                        ).roundToInt()
                    .coerceIn(-12, 12)

            if (imbalance > 0.12) {
                evidence++
                reasons +=
                    "Taker Buy غالب است"
            } else if (imbalance < -0.12) {
                evidence++
                reasons +=
                    "Taker Sell غالب است"
            }
        }

        /*
         * Long / Short
         */
        if (flow.longShortHistory.isNotEmpty()) {

            val latest =
                flow.longShortHistory.last()

            val ratio =
                latest.longShortRatio
                    .toDoubleOrNull()
                    ?: 1.0

            if (ratio > 1.8) {

                score -= 4
                evidence++

                reasons +=
                    "نسبت Long/Short بیش از حد به نفع لانگ است"

            } else if (ratio < 0.65) {

                score += 4
                evidence++

                reasons +=
                    "نسبت Long/Short به نفع شورت است؛ احتمال Short Squeeze وجود دارد"
            }
        }

        /*
         * Funding
         */
        flow.fundingRate?.let { funding ->

            if (funding > 0.0015) {

                score -= 5
                evidence++

                reasons +=
                    "Funding بالا؛ ریسک شلوغی لانگ"

            } else if (funding < -0.0015) {

                score += 5
                evidence++

                reasons +=
                    "Funding منفی؛ احتمال فشار شورت"
            }
        }

        val dataConfidence =
            periods.values
                .map { it.confidence }
                .average()
                .roundToInt()

        val confidence = (
                30 +
                        evidence * 8 +
                        dataConfidence / 5 +
                        if (
                            flow.openInterestHistory.isNotEmpty()
                        ) 8 else 0 +
                        if (
                            flow.takerVolumeHistory.isNotEmpty()
                        ) 8 else 0
                ).coerceIn(0, 95)

        val label =
            when {
                periods.values.count {
                    it.status == "ورود سنگین و غیرعادی"
                } >= 2 ->
                    "ورود سنگین و غیرعادی"

                periods.values.count {
                    it.status == "خروج سنگین و غیرعادی"
                } >= 2 ->
                    "خروج سنگین و غیرعادی"

                score >= 68 ->
                    "ورود پول"

                score <= 32 ->
                    "خروج پول"

                else ->
                    "خنثی"
            }

        return MoneyFlowResult(
            score.coerceIn(0, 100),
            confidence,
            label,
            reasons.distinct(),
            periods
        )
    }

    /**
     * محاسبه جریان پول برای 8 بازه
     */
    private fun calculateMoneyFlowPeriods(
        candles: List<Candle>
    ): Map<String, MoneyFlowPeriod> {

        val result =
            linkedMapOf<String, MoneyFlowPeriod>()

        moneyFlowPeriods.forEach { definition ->

            val currentDays =
                definition.days

            val currentStart =
                candles.size - currentDays

            if (currentStart < 0) return@forEach

            val current =
                candles.subList(
                    currentStart,
                    candles.size
                )

            val previousStart =
                currentStart - currentDays

            val previous =
                if (previousStart >= 0) {
                    candles.subList(
                        previousStart,
                        currentStart
                    )
                } else {
                    emptyList()
                }

            val currentFlow =
                calculateDirectionalFlow(current)

            val previousFlow =
                calculateDirectionalFlow(previous)

            val inflowChange =
                percentageChange(
                    currentFlow.inflow,
                    previousFlow.inflow
                )

            val outflowChange =
                percentageChange(
                    currentFlow.outflow,
                    previousFlow.outflow
                )

            val currentNet =
                currentFlow.inflow -
                        currentFlow.outflow

            val previousNet =
                previousFlow.inflow -
                        previousFlow.outflow

            val netChange =
                percentageChange(
                    abs(currentNet),
                    abs(previousNet)
                ).let {

                    if (
                        previousNet < 0 &&
                        currentNet > 0
                    ) {
                        abs(it)
                    } else if (
                        previousNet > 0 &&
                        currentNet < 0
                    ) {
                        -abs(it)
                    } else {
                        it
                    }
                }

            val baseline =
                candles
                    .take(currentStart)
                    .takeLast(
                        minOf(
                            60,
                            maxOf(
                                1,
                                currentStart
                            )
                        )
                    )

            val unusual =
                calculateUnusualIntensity(
                    current,
                    baseline
                )

            val status =
                determineMoneyFlowStatus(
                    currentFlow.inflow,
                    currentFlow.outflow,
                    unusual.first,
                    unusual.second
                )

            val confidence =
                calculatePeriodConfidence(
                    current,
                    previous,
                    definition.days
                )

            val periodReasons =
                mutableListOf<String>()

            if (inflowChange > 25) {
                periodReasons +=
                    "ورود پول نسبت به دوره قبل بیش از 25% افزایش یافته"
            }

            if (outflowChange > 25) {
                periodReasons +=
                    "خروج پول نسبت به دوره قبل بیش از 25% افزایش یافته"
            }

            if (unusual.first >= 75) {
                periodReasons +=
                    "شدت ورود پول غیرعادی است"
            }

            if (unusual.second >= 75) {
                periodReasons +=
                    "شدت خروج پول غیرعادی است"
            }

            if (currentNet > 0) {
                periodReasons +=
                    "خالص جریان این بازه مثبت است"
            } else if (currentNet < 0) {
                periodReasons +=
                    "خالص جریان این بازه منفی است"
            }

            result[definition.key] =
                MoneyFlowPeriod(
                    key = definition.key,
                    title = definition.title,
                    inflowUsd = currentFlow.inflow,
                    outflowUsd = currentFlow.outflow,
                    netFlowUsd = currentNet,
                    inflowChangePct = inflowChange,
                    outflowChangePct = outflowChange,
                    netChangePct = netChange,
                    unusualInflow = unusual.first,
                    unusualOutflow = unusual.second,
                    confidence = confidence,
                    status = status,
                    reasons = periodReasons
                )
        }

        return result
    }

    private data class DirectionalFlow(
        val inflow: Double,
        val outflow: Double
    )

    /**
     * تفکیک ارزش معاملات به ورود و خروج تخمینی
     *
     * Close نزدیک High:
     * فشار خرید بیشتر
     *
     * Close نزدیک Low:
     * فشار فروش بیشتر
     */
    private fun calculateDirectionalFlow(
        candles: List<Candle>
    ): DirectionalFlow {

        var inflow = 0.0
        var outflow = 0.0

        candles.forEach { candle ->

            val value =
                effectiveQuoteVolume(candle)

            if (value <= 0) return@forEach

            val range =
                (
                        candle.high -
                                candle.low
                        ).coerceAtLeast(1e-12)

            val multiplier =
                (
                        (
                                (candle.close - candle.low) -
                                        (candle.high - candle.close)
                                ) / range
                        ).coerceIn(-1.0, 1.0)

            /*
             * یک بخش پایه از حجم را خنثی نگه می‌داریم
             * و فقط بخش جهت‌دار را بین ورود/خروج تقسیم می‌کنیم.
             */
            val base =
                value * 0.5

            val directional =
                value * 0.5

            if (multiplier >= 0) {

                inflow +=
                    base +
                            directional * multiplier

                outflow +=
                    base -
                            directional * multiplier

            } else {

                inflow +=
                    base +
                            directional * multiplier

                outflow +=
                    base -
                            directional * multiplier
            }
        }

        return DirectionalFlow(
            inflow = inflow,
            outflow = outflow
        )
    }

    private fun effectiveQuoteVolume(
        candle: Candle
    ): Double {

        return when {
            candle.quoteVolume > 0 ->
                candle.quoteVolume

            candle.volume > 0 &&
                    candle.close > 0 ->
                candle.volume * candle.close

            else ->
                0.0
        }
    }

    /**
     * شدت غیرعادی ورود/خروج
     *
     * خروجی 0 تا 100
     */
    private fun calculateUnusualIntensity(
        current: List<Candle>,
        baseline: List<Candle>
    ): Pair<Int, Int> {

        if (
            current.isEmpty() ||
            baseline.isEmpty()
        ) {
            return 50 to 50
        }

        val currentFlow =
            calculateDirectionalFlow(current)

        val baselineFlows =
            baseline.map {
                calculateDirectionalFlow(
                    listOf(it)
                )
            }

        val baselineInflow =
            baselineFlows
                .map { it.inflow }
                .filter { it > 0 }

        val baselineOutflow =
            baselineFlows
                .map { it.outflow }
                .filter { it > 0 }

        val avgInflow =
            baselineInflow
                .average()
                .coerceAtLeast(1.0)

        val avgOutflow =
            baselineOutflow
                .average()
                .coerceAtLeast(1.0)

        val currentDays =
            current.size.coerceAtLeast(1)

        val currentDailyInflow =
            currentFlow.inflow /
                    currentDays

        val currentDailyOutflow =
            currentFlow.outflow /
                    currentDays

        val baselineInflowStd =
            baselineInflow
                .standardDeviation()
                .coerceAtLeast(
                    avgInflow * 0.15
                )

        val baselineOutflowStd =
            baselineOutflow
                .standardDeviation()
                .coerceAtLeast(
                    avgOutflow * 0.15
                )

        val inflowZ =
            (
                    currentDailyInflow -
                            avgInflow
                    ) / baselineInflowStd

        val outflowZ =
            (
                    currentDailyOutflow -
                            avgOutflow
                    ) / baselineOutflowStd

        val inflowRatio =
            currentDailyInflow /
                    avgInflow

        val outflowRatio =
            currentDailyOutflow /
                    avgOutflow

        val inflowScore =
            (
                    50 +
                            inflowZ * 18 +
                            (inflowRatio - 1) * 20
                    )
                .roundToInt()
                .coerceIn(0, 100)

        val outflowScore =
            (
                    50 +
                            outflowZ * 18 +
                            (outflowRatio - 1) * 20
                    )
                .roundToInt()
                .coerceIn(0, 100)

        return inflowScore to outflowScore
    }

    private fun determineMoneyFlowStatus(
        inflow: Double,
        outflow: Double,
        unusualInflow: Int,
        unusualOutflow: Int
    ): String {

        val total =
            (inflow + outflow)
                .coerceAtLeast(1.0)

        val netRatio =
            (inflow - outflow) / total

        return when {

            netRatio >= 0.12 &&
                    unusualInflow >= 75 ->
                "ورود سنگین و غیرعادی"

            netRatio <= -0.12 &&
                    unusualOutflow >= 75 ->
                "خروج سنگین و غیرعادی"

            netRatio >= 0.08 ->
                "ورود پول"

            netRatio <= -0.08 ->
                "خروج پول"

            else ->
                "خنثی"
        }
    }

    private fun calculatePeriodConfidence(
        current: List<Candle>,
        previous: List<Candle>,
        days: Int
    ): Int {

        var confidence = 45

        if (current.size >= days) {
            confidence += 20
        }

        if (previous.size >= days) {
            confidence += 15
        }

        if (days >= 30) {
            confidence += 10
        }

        if (days >= 90) {
            confidence += 5
        }

        return confidence.coerceIn(0, 95)
    }

    private fun percentageChange(
        current: Double,
        previous: Double
    ): Double {

        if (previous <= 1e-9) {

            return when {
                current > 0 -> 100.0
                else -> 0.0
            }
        }

        return (
                (current - previous) /
                        previous
                ) * 100.0
    }

    private fun cmf(
        c: List<Candle>
    ): Double {

        var mfv = 0.0
        var vol = 0.0

        c.forEach { x ->

            val range =
                (x.high - x.low)
                    .coerceAtLeast(1e-9)

            val value =
                effectiveQuoteVolume(x)

            val multiplier =
                (
                        (
                                (x.close - x.low) -
                                        (x.high - x.close)
                                ) / range
                        )

            mfv +=
                multiplier * value

            vol += value
        }

        return if (vol == 0.0) {
            0.0
        } else {
            mfv / vol
        }
    }

    private fun obvSlope(
        c: List<Candle>
    ): Double {

        if (c.size < 3) return 0.0

        var obv = 0.0

        val series =
            mutableListOf<Double>()

        for (i in 1 until c.size) {

            val value =
                effectiveQuoteVolume(c[i])

            obv +=
                when {

                    c[i].close >
                            c[i - 1].close ->
                        value

                    c[i].close <
                            c[i - 1].close ->
                        -value

                    else ->
                        0.0
                }

            series += obv
        }

        val denom =
            c.sumOf {
                effectiveQuoteVolume(it)
            }.coerceAtLeast(1e-9)

        return (
                (series.last() - series.first()) /
                        denom
                ).coerceIn(-1.0, 1.0)
    }

    private fun List<Double>.standardDeviation(): Double {

        if (size < 2) return 0.0

        val m =
            average()

        return sqrt(
            sumOf {
                (it - m).pow(2)
            } / (size - 1)
        )
    }

    private fun scoreFrame(
        c: List<Candle>
    ): Int {

        if (c.size < 25) return 50

        val x =
            c.map { it.close }

        val last =
            x.last()

        val ma20 =
            x.takeLast(20).average()

        val ma10 =
            x.takeLast(10).average()

        val ret =
            last /
                    x[x.size - 21] -
                    1

        return (
                50 +
                        if (last > ma20) 15 else -15 +
                        if (ma10 > ma20) 15 else -15 +
                        (
                                ret.coerceIn(
                                    -0.15,
                                    0.15
                                ) * 100
                                ).roundToInt()
                ).coerceIn(0, 100)
    }

    private fun resample(
        src: List<Candle>,
        days: Int
    ): List<Candle> {

        if (days == 1) return src

        val out =
            mutableListOf<Candle>()

        var i = 0

        while (i < src.size) {

            val end =
                min(
                    i + days,
                    src.size
                )

            val group =
                src.subList(
                    i,
                    end
                )

            out += Candle(
                close = group.last().close,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                volume = group.sumOf { it.volume },
                quoteVolume = group.sumOf {
                    effectiveQuoteVolume(it)
                },
                openTime = group.first().openTime
            )

            i += days
        }

        return out
    }

    private fun analyzeStructure(
        c: List<Candle>
    ): StructureResult {

        if (c.size < 40) {

            return StructureResult(
                50,
                "NEUTRAL",
                false,
                false,
                c.minOf { it.low },
                c.maxOf { it.high },
                listOf("داده ساختار کافی نیست")
            )
        }

        val recent =
            c.takeLast(40)

        val support =
            recent
                .dropLast(3)
                .takeLast(20)
                .minOf { it.low }

        val resistance =
            recent
                .dropLast(3)
                .takeLast(20)
                .maxOf { it.high }

        val last =
            recent.last().close

        val atrValue =
            atr(c, 14)

        val breakout =
            last >
                    resistance +
                    atrValue * 0.15

        val breakdown =
            last <
                    support -
                    atrValue * 0.15

        val score =
            when {

                breakout -> 88

                breakdown -> 18

                last >
                        resistance * 0.985 ->
                    72

                last <
                        support * 1.015 ->
                    28

                else -> 50
            }

        val label =
            when {
                breakout -> "BREAKOUT"
                breakdown -> "BREAKDOWN"
                else -> "RANGE"
            }

        val rs =
            mutableListOf<String>()

        if (breakout) {
            rs +=
                "شکست مقاومت با فاصله‌ای بالاتر از ATR"
        }

        if (breakdown) {
            rs +=
                "شکست حمایت با فاصله‌ای بالاتر از ATR"
        }

        if (!breakout && !breakdown) {
            rs +=
                "قیمت هنوز در محدوده حمایت/مقاومت است"
        }

        return StructureResult(
            score,
            label,
            breakout,
            breakdown,
            support,
            resistance,
            rs
        )
    }

    private fun analyzeDivergence(
        c: List<Candle>
    ): DivergenceResult {

        if (c.size < 50) {

            return DivergenceResult(
                50,
                "NONE",
                false,
                false,
                emptyList()
            )
        }

        val a =
            c.takeLast(35)

        val half =
            a.size / 2

        val p1 =
            a.take(half)
                .map { it.close }
                .average()

        val p2 =
            a.takeLast(half)
                .map { it.close }
                .average()

        val r1 =
            rsiSeries(
                a.take(half)
            ).average()

        val r2 =
            rsiSeries(
                a.takeLast(half)
            ).average()

        val bullish =
            p2 < p1 &&
                    r2 > r1 + 2.5

        val bearish =
            p2 > p1 &&
                    r2 < r1 - 2.5

        return when {

            bullish ->
                DivergenceResult(
                    68,
                    "BULLISH",
                    true,
                    false,
                    listOf(
                        "واگرایی مثبت تقریبی قیمت/RSI"
                    )
                )

            bearish ->
                DivergenceResult(
                    32,
                    "BEARISH",
                    false,
                    true,
                    listOf(
                        "واگرایی منفی تقریبی قیمت/RSI"
                    )
                )

            else ->
                DivergenceResult(
                    50,
                    "NONE",
                    false,
                    false,
                    listOf(
                        "واگرایی مهمی دیده نشد"
                    )
                )
        }
    }

    private fun rsiSeries(
        c: List<Candle>
    ): List<Double> {

        val x =
            c.map { it.close }

        if (x.size < 16) {
            return listOf(50.0)
        }

        val out =
            mutableListOf<Double>()

        for (i in 15 until x.size) {

            var g = 0.0
            var l = 0.0

            for (j in i - 13..i) {

                val d =
                    x[j] - x[j - 1]

                if (d > 0) {
                    g += d
                } else {
                    l -= d
                }
            }

            out +=
                if (l == 0.0) {

                    100.0

                } else {

                    100 - 100 /
                            (
                                    1 +
                                            (g / 14) /
                                            (l / 14)
                                    )
                }
        }

        return out
    }

    private fun analyzeBtcRegime(
        c: List<Candle>
    ): BtcRegime {

        if (c.size < 60) {

            return BtcRegime(
                50,
                "UNKNOWN",
                listOf(
                    "داده BTC کافی نیست"
                )
            )
        }

        val x =
            c.map { it.close }

        val last =
            x.last()

        val e20 =
            ema(x, 20)

        val e50 =
            ema(x, 50)

        val ret =
            x.last() /
                    x[x.size - 21] -
                    1

        var score = 50

        val r =
            mutableListOf<String>()

        if (last > e20) {
            score += 10
            r += "BTC بالای EMA20"
        } else {
            score -= 10
            r += "BTC زیر EMA20"
        }

        if (e20 > e50) {
            score += 12
            r += "BTC روند میان‌مدت صعودی"
        } else {
            score -= 12
        }

        if (ret > 0.05) {
            score += 10
            r += "مومنتوم BTC مثبت"
        } else if (ret < -0.05) {
            score -= 10
            r += "مومنتوم BTC منفی"
        }

        val label =
            when {
                score >= 68 -> "RISK-ON"
                score <= 32 -> "RISK-OFF"
                else -> "NEUTRAL"
            }

        return BtcRegime(
            score.coerceIn(0, 100),
            label,
            r
        )
    }

    private fun buildTradePlan(
        c: List<Candle>,
        s: StructureResult,
        signal: String
    ): TradePlan? {

        if (signal == "WAIT") return null

        val last =
            c.last().close

        val atrValue =
            atr(c, 14)

        val entryLow =
            if (signal == "BUY") {

                max(
                    s.resistance * 0.995,
                    last - atrValue * 0.25
                )

            } else {

                min(
                    s.support * 1.005,
                    last + atrValue * 0.25
                )
            }

        val entryHigh =
            if (signal == "BUY") {

                last + atrValue * 0.25

            } else {

                last + atrValue * 0.10
            }

        return if (signal == "BUY") {

            val sl =
                min(
                    s.support,
                    last - atrValue * 1.5
                )

            val risk =
                (last - sl)
                    .coerceAtLeast(
                        atrValue * 0.5
                    )

            TradePlan(
                entryLow,
                entryHigh,
                sl,
                last + risk * 1.5,
                last + risk * 2.5,
                (risk * 2.0 / risk)
                    .coerceAtLeast(1.0)
            )

        } else {

            val sl =
                max(
                    s.resistance,
                    last + atrValue * 1.5
                )

            val risk =
                (sl - last)
                    .coerceAtLeast(
                        atrValue * 0.5
                    )

            TradePlan(
                entryLow,
                entryHigh,
                sl,
                last - risk * 1.5,
                last - risk * 2.5,
                (risk * 2.0 / risk)
                    .coerceAtLeast(1.0)
            )
        }
    }

    private fun atr(
        c: List<Candle>,
        p: Int
    ): Double {

        if (c.size < p + 1) {

            return c
                .takeLast(
                    minOf(
                        p,
                        c.size
                    )
                )
                .map {
                    it.high - it.low
                }
                .average()
        }

        val tr =
            mutableListOf<Double>()

        for (
            i in c.size - p until c.size
        ) {

            val x =
                c[i]

            val prev =
                c[i - 1].close

            tr +=
                maxOf(
                    x.high - x.low,
                    abs(x.high - prev),
                    abs(x.low - prev)
                )
        }

        return tr.average()
            .coerceAtLeast(1e-9)
    }

    private fun ema(
        x: List<Double>,
        p: Int
    ): Double {

        val n =
            minOf(
                p,
                x.size
            )

        val k =
            2.0 / (n + 1)

        var e =
            x.take(n).average()

        for (i in n until x.size) {

            e =
                x[i] * k +
                        e * (1 - k)
        }

        return e
    }
}
