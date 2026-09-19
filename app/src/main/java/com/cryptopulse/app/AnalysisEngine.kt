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
val moneyFlow: Double = 0.24,
val timeframe: Double = 0.18,
val structure: Double = 0.10,
val divergence: Double = 0.06,
val news: Double = 0.10,
val btc: Double = 0.08
) {
fun normalized(): AnalysisWeights {

    val sum =
        technical +
            moneyFlow +
            timeframe +
            structure +
            divergence +
            news +
            btc

    if (sum <= 0) {
        return AnalysisWeights()
    }

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

val tradePlan: TradePlan? = null,

val finalAnalysis: String =
    "تحلیل نهایی: داده کافی برای جمع‌بندی وجود ندارد"

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

private val periods = listOf(
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
            score = 0,
            pump = 0,
            dump = 0,
            confidence = 0,
            signal = "WAIT",
            reasons = listOf(
                "داده کافی برای تحلیل نهایی وجود ندارد"
            ),
            finalAnalysis =
                "تحلیل نهایی: داده کافی نیست"
        )
    }

    val timeframeScores =
        mapOf(
            "1D" to 1,
            "2D" to 2,
            "3D" to 3,
            "4D" to 4,
            "1W" to 7,
            "1M" to 30
        ).mapValues { (_, days) ->
            scoreFrame(
                resample(candles, days)
            )
        }

    val weightedTimeframe =
        (
            timeframeScores["1M"]!! * 0.25 +
                timeframeScores["1W"]!! * 0.25 +
                timeframeScores["4D"]!! * 0.15 +
                timeframeScores["3D"]!! * 0.10 +
                timeframeScores["2D"]!! * 0.10 +
                timeframeScores["1D"]!! * 0.15
            ).roundToInt()

    val core =
        scoreCore(candles)

    val money =
        scoreMoneyFlow(
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

    val w =
        weights.normalized()

    var score =
        (
            core.tech * w.technical +
                money.score * w.moneyFlow +
                weightedTimeframe * w.timeframe +
                structure.score * w.structure +
                divergence.score * w.divergence +
                newsScore.coerceIn(0, 100) * w.news +
                btc.score * w.btc
            ).roundToInt()

    val heavyInflowPeriods =
        money.periods.values.count {
            it.status == "ورود سنگین و غیرعادی"
        }

    val heavyOutflowPeriods =
        money.periods.values.count {
            it.status == "خروج سنگین و غیرعادی"
        }

    val bullishMoney =
        money.score >= 68

    val bearishMoney =
        money.score <= 32

    val bullishTechnical =
        core.tech >= 65 &&
            weightedTimeframe >= 60

    val bearishTechnical =
        core.tech <= 35 &&
            weightedTimeframe <= 40

    val moneyConflict =
        (
            bullishTechnical &&
                heavyOutflowPeriods >= 2
            ) ||
            (
                bearishTechnical &&
                    heavyInflowPeriods >= 2
            )

    if (heavyInflowPeriods >= 2) {
        score += 6
    }

    if (heavyOutflowPeriods >= 2) {
        score -= 6
    }

    if (
        bullishTechnical &&
        bullishMoney
    ) {
        score += 4
    }

    if (
        bearishTechnical &&
        bearishMoney
    ) {
        score -= 4
    }

    if (
        structure.breakout &&
        heavyInflowPeriods >= 1
    ) {
        score += 4
    }

    if (
        structure.breakdown &&
        heavyOutflowPeriods >= 1
    ) {
        score -= 4
    }

    val divergenceConflict =
        (
            bullishTechnical &&
                divergence.bearish
            ) ||
            (
                bearishTechnical &&
                    divergence.bullish
            )

    if (divergenceConflict) {

        score =
            when {
                score > 50 ->
                    min(score, 76)

                score < 50 ->
                    max(score, 24)

                else ->
                    score
            }
    }

    if (moneyConflict) {

        score =
            when {
                score > 60 ->
                    min(score, 62)

                score < 40 ->
                    max(score, 38)

                else ->
                    score
            }
    }

    if (
        newsConfidence >= 65 &&
        newsScore <= 25 &&
        score > 58
    ) {
        score -= 5
    }

    if (
        newsConfidence >= 65 &&
        newsScore >= 75 &&
        score < 42
    ) {
        score += 5
    }

    score =
        score.coerceIn(0, 100)

    val pump =
        (
            50 +
                (score - 50) * 1.35
            ).roundToInt()
            .coerceIn(1, 98)

    val dump =
        (
            50 +
                (50 - score) * 1.35
            ).roundToInt()
            .coerceIn(1, 98)

    val bullishComponents =
        listOf(
            core.tech >= 60,
            weightedTimeframe >= 60,
            money.score >= 60,
            structure.score >= 60,
            divergence.score >= 60,
            btc.score >= 60
        ).count { it }

    val bearishComponents =
        listOf(
            core.tech <= 40,
            weightedTimeframe <= 40,
            money.score <= 40,
            structure.score <= 40,
            divergence.score <= 40,
            btc.score <= 40
        ).count { it }

    val disagreement =
        listOf(
            core.tech <= 40,
            weightedTimeframe <= 40,
            money.score <= 40,
            structure.score <= 40,
            divergence.score <= 40,
            btc.score <= 40
        ).count { it }

    val baseConfidence =
        42 +
            minOf(12, candles.size / 40) +
            maxOf(
                bullishComponents,
                bearishComponents
            ) * 5 +
            minOf(
                15,
                money.confidence / 7
            ) +
            if (newsConfidence >= 60) 8 else 0 +
            if (flow.openInterestHistory.isNotEmpty()) 5 else 0 +
            if (flow.takerVolumeHistory.isNotEmpty()) 5 else 0 -
            minOf(
                12,
                disagreement * 2
            ) -
            if (moneyConflict) 10 else 0 -
            if (divergenceConflict) 6 else 0

    val confidence =
        baseConfidence.coerceIn(0, 98)

    val signal =
        when {

            confidence < 58 ->
                "HOLD"

            moneyConflict ->
                "HOLD"

            divergenceConflict &&
                confidence < 78 ->
                "HOLD"

            score >= 82 &&
                confidence >= 78 &&
                heavyInflowPeriods >= 2 &&
                bullishTechnical &&
                bullishMoney &&
                btc.score >= 55 ->
                "STRONG BUY"

            score >= 70 &&
                confidence >= 64 &&
                (
                    bullishMoney &&
                        bullishTechnical
                    ||
                    bullishMoney &&
                        structure.score >= 60
                    ||
                    bullishTechnical &&
                        structure.breakout
                ) ->
                "BUY"

            score <= 18 &&
                confidence >= 78 &&
                heavyOutflowPeriods >= 2 &&
                bearishTechnical &&
                bearishMoney &&
                btc.score <= 45 ->
                "STRONG SELL"

            score <= 30 &&
                confidence >= 64 &&
                (
                    bearishMoney &&
                        bearishTechnical
                    ||
                    bearishMoney &&
                        structure.score <= 40
                    ||
                    bearishTechnical &&
                        structure.breakdown
                ) ->
                "SELL"

            else ->
                "HOLD"
        }

    val reasons =
        mutableListOf<String>()

    reasons += core.reasons
    reasons += money.reasons
    reasons += structure.reasons
    reasons += divergence.reasons
    reasons += btc.reasons

    if (heavyInflowPeriods >= 2) {
        reasons +=
            "ورود سنگین و غیرعادی پول در چند بازه زمانی تأیید شده است"
    }

    if (heavyOutflowPeriods >= 2) {
        reasons +=
            "خروج سنگین و غیرعادی پول در چند بازه زمانی تأیید شده است"
    }

    if (
        bullishTechnical &&
        bullishMoney
    ) {
        reasons +=
            "روند تکنیکال و جریان پول هم‌جهت هستند"
    }

    if (
        bearishTechnical &&
        bearishMoney
    ) {
        reasons +=
            "روند تکنیکال و جریان پول نزولی و هم‌جهت هستند"
    }

    if (moneyConflict) {
        reasons +=
            "بین جریان پول و روند تکنیکال تضاد وجود دارد؛ سیگنال به نگهداری/انتظار کاهش یافت"
    }

    if (divergenceConflict) {
        reasons +=
            "واگرایی با جهت اصلی روند در تضاد است"
    }

    if (structure.breakout) {
        reasons +=
            "ساختار بازار شکست صعودی نشان می‌دهد"
    }

    if (structure.breakdown) {
        reasons +=
            "ساختار بازار شکست نزولی نشان می‌دهد"
    }

    reasons +=
        "امتیاز اخبار: $newsScore/100"

    if (newsConfidence >= 60) {
        reasons +=
            "اعتماد تحلیل خبر: $newsConfidence/100"
    }

    val plan =
        buildTradePlan(
            candles,
            structure,
            signal
        )

    if (plan != null) {

        when {

            signal == "BUY" ||
                signal == "STRONG BUY" -> {

                reasons +=
                    "🎯 محدوده ورود نزدیک قیمت فعلی: " +
                        "${formatPrice(plan.entryLow)} تا " +
                        formatPrice(plan.entryHigh)

                reasons +=
                    "🎯 محدوده خروج هدف: " +
                        "${formatPrice(plan.tp1)} تا " +
                        formatPrice(plan.tp2)

                reasons +=
                    "🛑 حد خروج اضطراری: " +
                        formatPrice(plan.stopLoss)

                reasons +=
                    "📊 نسبت ریسک به بازده: " +
                        String.format(
                            java.util.Locale.US,
                            "%.2f",
                            plan.riskReward
                        )
            }

            signal == "SELL" ||
                signal == "STRONG SELL" -> {

                reasons +=
                    "🎯 محدوده ورود فروش نزدیک قیمت فعلی: " +
                        "${formatPrice(plan.entryLow)} تا " +
                        formatPrice(plan.entryHigh)

                reasons +=
                    "🎯 محدوده خروج احتمالی: " +
                        "${formatPrice(plan.tp2)} تا " +
                        formatPrice(plan.tp1)

                reasons +=
                    "📉 هدف نزولی: " +
                        "${formatPrice(plan.tp2)} تا " +
                        formatPrice(plan.tp1)

                reasons +=
                    "🛑 حد خروج اضطراری: " +
                        formatPrice(plan.stopLoss)

                reasons +=
                    "📊 نسبت ریسک به بازده: " +
                        String.format(
                            java.util.Locale.US,
                            "%.2f",
                            plan.riskReward
                        )
            }
        }
    }

    val finalAnalysis =
        buildFinalAnalysis(
            signal = signal,
            score = score,
            confidence = confidence,
            money = money,
            weightedTimeframe = weightedTimeframe,
            structure = structure,
            divergence = divergence,
            btc = btc,
            heavyInflowPeriods = heavyInflowPeriods,
            heavyOutflowPeriods = heavyOutflowPeriods,
            moneyConflict = moneyConflict,
            divergenceConflict = divergenceConflict,
            tradePlan = plan
        )

    return AnalysisResult(
        score = score,
        pump = pump,
        dump = dump,
        confidence = confidence,
        signal = signal,
        reasons = reasons.distinct(),
        moneyFlow = money.score,
        trend = weightedTimeframe,
        news = newsScore,
        timeframeScores = timeframeScores,
        moneyFlowDetails = money,
        structure = structure,
        divergence = divergence,
        btcRegime = btc,
        tradePlan = plan,
        finalAnalysis = finalAnalysis
    )
}

private fun buildFinalAnalysis(
    signal: String,
    score: Int,
    confidence: Int,
    money: MoneyFlowResult,
    weightedTimeframe: Int,
    structure: StructureResult,
    divergence: DivergenceResult,
    btc: BtcRegime,
    heavyInflowPeriods: Int,
    heavyOutflowPeriods: Int,
    moneyConflict: Boolean,
    divergenceConflict: Boolean,
    tradePlan: TradePlan?
): String {

    val signalText =
        when (signal) {
            "STRONG BUY" ->
                "🟢 خرید قوی"

            "BUY" ->
                "🟢 خرید"

            "STRONG SELL" ->
                "🔴 فروش قوی"

            "SELL" ->
                "🔴 فروش"

            else ->
                "🟡 نگهداری / انتظار"
        }

    val parts =
        mutableListOf<String>()

    parts +=
        "تحلیل نهایی: $signalText"

    parts +=
        "امتیاز: $score/100"

    parts +=
        "اعتماد: $confidence/100"

    parts +=
        "روند چندتایم‌فریمی: $weightedTimeframe/100"

    parts +=
        "جریان پول: ${money.score}/100"

    when {
        heavyInflowPeriods >= 2 ->
            parts +=
                "ورود پول غیرعادی در $heavyInflowPeriods بازه تأیید شده"

        heavyOutflowPeriods >= 2 ->
            parts +=
                "خروج پول غیرعادی در $heavyOutflowPeriods بازه تأیید شده"

        money.score >= 68 ->
            parts +=
                "جریان پول متمایل به ورود است"

        money.score <= 32 ->
            parts +=
                "جریان پول متمایل به خروج است"

        else ->
            parts +=
                "جریان پول وضعیت خنثی دارد"
    }

    when {
        structure.breakout ->
            parts +=
                "ساختار: شکست صعودی مقاومت"

        structure.breakdown ->
            parts +=
                "ساختار: شکست نزولی حمایت"

        else ->
            parts +=
                "ساختار: بازار در محدوده قرار دارد"
    }

    when {
        divergence.bullish ->
            parts +=
                "واگرایی: مثبت"

        divergence.bearish ->
            parts +=
                "واگرایی: منفی"

        else ->
            parts +=
                "واگرایی مهمی تأیید نشده"
    }

    parts +=
        "وضعیت BTC: ${btc.label}"

    if (moneyConflict) {
        parts +=
            "هشدار: تضاد بین جریان پول و روند وجود دارد"
    }

    if (divergenceConflict) {
        parts +=
            "هشدار: واگرایی با جهت اصلی روند هم‌جهت نیست"
    }

    when {

        (
            signal == "BUY" ||
                signal == "STRONG BUY"
            ) &&
            tradePlan != null -> {

            parts +=
                "🎯 محدوده ورود نزدیک قیمت فعلی: " +
                    "${formatPrice(tradePlan.entryLow)} تا " +
                    formatPrice(tradePlan.entryHigh)

            parts +=
                "🎯 محدوده خروج هدف: " +
                    "${formatPrice(tradePlan.tp1)} تا " +
                    formatPrice(tradePlan.tp2)

            parts +=
                "🛑 حد خروج اضطراری: " +
                    formatPrice(tradePlan.stopLoss)

            parts +=
                "📊 نسبت ریسک به بازده: " +
                    String.format(
                        java.util.Locale.US,
                        "%.2f",
                        tradePlan.riskReward
                    )
        }

        (
            signal == "SELL" ||
                signal == "STRONG SELL"
            ) &&
            tradePlan != null -> {

            parts +=
                "🎯 محدوده ورود فروش نزدیک قیمت فعلی: " +
                    "${formatPrice(tradePlan.entryLow)} تا " +
                    formatPrice(tradePlan.entryHigh)

            parts +=
                "🎯 محدوده خروج احتمالی: " +
                    "${formatPrice(tradePlan.tp2)} تا " +
                    formatPrice(tradePlan.tp1)

            parts +=
                "📉 هدف نزولی: " +
                    "${formatPrice(tradePlan.tp2)} تا " +
                    formatPrice(tradePlan.tp1)

            parts +=
                "🛑 حد خروج اضطراری: " +
                    formatPrice(tradePlan.stopLoss)

            parts +=
                "📊 نسبت ریسک به بازده: " +
                    String.format(
                        java.util.Locale.US,
                        "%.2f",
                        tradePlan.riskReward
                    )
        }
    }

    return parts.joinToString(" • ")
}

private fun formatPrice(
    value: Double
): String {

    if (!value.isFinite()) {
        return "-"
    }

    return when {
        value >= 1000 ->
            String.format(
                java.util.Locale.US,
                "%.2f",
                value
            )

        value >= 1 ->
            String.format(
                java.util.Locale.US,
                "%.4f",
                value
            )

        value >= 0.01 ->
            String.format(
                java.util.Locale.US,
                "%.6f",
                value
            )

        else ->
            String.format(
                java.util.Locale.US,
                "%.8f",
                value
            )
    }
}

private data class Core(
    val tech: Int,
    val reasons: List<String>
)

private fun scoreCore(
    candles: List<Candle>
): Core {

    val x =
        candles.map { it.close }

    val last =
        x.last()

    fun ema(period: Int) =
        ema(x, period)

    fun rsi(period: Int = 14): Double {

        if (x.size <= period) {
            return 50.0
        }

        var gain = 0.0
        var loss = 0.0

        for (
            i in x.size - period until x.size
        ) {

            val d =
                x[i] - x[i - 1]

            if (d >= 0) {
                gain += d
            } else {
                loss -= d
            }
        }

        if (loss == 0.0) {
            return 100.0
        }

        val rs =
            (gain / period) /
                (loss / period)

        return 100 -
            100 / (1 + rs)
    }

    val e20 =
        ema(20)

    val e50 =
        ema(50)

    val e100 =
        ema(100)

    val e200 =
        ema(200)

    val rsi =
        rsi()

    var score = 50

    val reasons =
        mutableListOf<String>()

    if (last > e20) {
        score += 8
        reasons +=
            "قیمت بالای EMA20"
    } else {
        score -= 8
    }

    if (e20 > e50) {
        score += 8
        reasons +=
            "EMA20 بالای EMA50"
    } else {
        score -= 8
    }

    if (e50 > e100) {
        score += 6
        reasons +=
            "روند میان‌مدت صعودی"
    } else {
        score -= 6
    }

    if (last > e200) {
        score += 8
        reasons +=
            "قیمت بالای EMA200"
    } else {
        score -= 8
    }

    when {

        rsi in 52.0..68.0 -> {
            score += 7
            reasons +=
                "RSI در محدوده سالم"
        }

        rsi > 75 -> {
            score -= 5
            reasons +=
                "RSI بیش‌خرید"
        }

        rsi < 30 -> {
            score += 3
            reasons +=
                "RSI اشباع فروش"
        }
    }

    return Core(
        score.coerceIn(0, 100),
        reasons
    )
}

private fun scoreMoneyFlow(
    candles: List<Candle>,
    flow: MarketFlowData
): MoneyFlowResult {

    val periods =
        calculateMoneyFlowPeriods(candles)

    val weights =
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

    var weighted = 0.0
    var totalWeight = 0.0

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
            weights[p.key] ?: 0.10

        weighted +=
            direction * weight

        totalWeight += weight
    }

    var score =
        50 +
            (
                weighted /
                    totalWeight.coerceAtLeast(0.01) *
                    65
                ).roundToInt()

    val reasons =
        mutableListOf<String>()

    periods.values.forEach { p ->

        if (p.unusualInflow >= 75) {
            reasons +=
                "${p.title}: ورود پول غیرعادی و سنگین"
        }

        if (p.unusualOutflow >= 75) {
            reasons +=
                "${p.title}: خروج پول غیرعادی و سنگین"
        }
    }

    val recent =
        candles.takeLast(31)

    val volumes =
        recent.map {
            effectiveQuoteVolume(it)
        }

    if (volumes.size >= 3) {

        val average =
            volumes.dropLast(1)
                .average()
                .coerceAtLeast(1.0)

        val ratio =
            volumes.last() / average

        if (ratio >= 2.0) {
            score += 5
            reasons +=
                "ارزش معاملات روز جاری بیش از دو برابر میانگین است"
        } else if (ratio >= 1.4) {
            score += 2
        }
    }

    val cmfValue =
        cmf(
            candles.takeLast(20)
        )

    score +=
        (cmfValue * 20)
            .roundToInt()
            .coerceIn(-12, 12)

    if (cmfValue > 0.15) {
        reasons +=
            "CMF مثبت؛ فشار خرید بیشتر است"
    } else if (cmfValue < -0.15) {
        reasons +=
            "CMF منفی؛ فشار فروش بیشتر است"
    }

    val obv =
        obvSlope(
            candles.takeLast(20)
        )

    score +=
        (obv * 10)
            .roundToInt()
            .coerceIn(-8, 8)

    if (
        flow.openInterestHistory.size >= 2
    ) {

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

            val change =
                last / first - 1

            when {

                change > 0.05 -> {
                    score += 6
                    reasons +=
                        "Open Interest بیش از 5٪ افزایش یافته"
                }

                change < -0.05 -> {
                    score -= 5
                    reasons +=
                        "Open Interest بیش از 5٪ کاهش یافته"
                }
            }
        }
    }

    if (
        flow.takerVolumeHistory.isNotEmpty()
    ) {

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
            (
                buy + sell
                ).coerceAtLeast(1.0)

        val imbalance =
            (buy - sell) / total

        score +=
            (imbalance * 25)
                .roundToInt()
                .coerceIn(-10, 10)

        if (imbalance > 0.12) {
            reasons +=
                "Taker Buy غالب است"
        } else if (imbalance < -0.12) {
            reasons +=
                "Taker Sell غالب است"
        }
    }

    flow.longShortHistory.lastOrNull()?.let {

        val ratio =
            it.longShortRatio
                .toDoubleOrNull()
                ?: 1.0

        if (ratio > 1.8) {
            score -= 3
            reasons +=
                "لانگ‌ها نسبتاً شلوغ هستند"
        }

        if (ratio < 0.65) {
            score += 3
            reasons +=
                "شورت‌ها غالب هستند؛ احتمال Short Squeeze وجود دارد"
        }
    }

    flow.fundingRate?.let {

        when {

            it > 0.0015 -> {
                score -= 4
                reasons +=
                    "Funding بالا است"
            }

            it < -0.0015 -> {
                score += 4
                reasons +=
                    "Funding منفی است"
            }
        }
    }

    val heavyInflow =
        periods.values.count {
            it.status == "ورود سنگین و غیرعادی"
        }

    val heavyOutflow =
        periods.values.count {
            it.status == "خروج سنگین و غیرعادی"
        }

    val label =
        when {

            heavyInflow >= 2 ->
                "ورود سنگین و غیرعادی"

            heavyOutflow >= 2 ->
                "خروج سنگین و غیرعادی"

            score >= 68 ->
                "ورود پول"

            score <= 32 ->
                "خروج پول"

            else ->
                "خنثی"
        }

    val confidence =
        (
            30 +
                minOf(
                    30,
                    periods.size * 3
                ) +
                heavyInflow * 6 +
                heavyOutflow * 6 +
                if (
                    flow.openInterestHistory.isNotEmpty()
                ) 8 else 0 +
                if (
                    flow.takerVolumeHistory.isNotEmpty()
                ) 8 else 0
            ).coerceIn(0, 95)

    return MoneyFlowResult(
        score.coerceIn(0, 100),
        confidence,
        label,
        reasons.distinct(),
        periods
    )
}

private data class DirectionalFlow(
    val inflow: Double,
    val outflow: Double
)

private fun calculateMoneyFlowPeriods(
    candles: List<Candle>
): Map<String, MoneyFlowPeriod> {

    val result =
        linkedMapOf<String, MoneyFlowPeriod>()

    periods.forEach { definition ->

        if (
            candles.size <
            definition.days
        ) {
            return@forEach
        }

        val start =
            candles.size -
                definition.days

        val current =
            candles.subList(
                start,
                candles.size
            )

        val previousStart =
            start -
                definition.days

        val previous =
            if (previousStart >= 0) {
                candles.subList(
                    previousStart,
                    start
                )
            } else {
                emptyList()
            }

        val currentFlow =
            directionalFlow(current)

        val previousFlow =
            directionalFlow(previous)

        val currentNet =
            currentFlow.inflow -
                currentFlow.outflow

        val previousNet =
            previousFlow.inflow -
                previousFlow.outflow

        val unusual =
            unusualIntensity(
                current,
                candles
                    .take(start)
                    .takeLast(60)
            )

        val status =
            status(
                currentFlow.inflow,
                currentFlow.outflow,
                unusual.first,
                unusual.second
            )

        val reasons =
            mutableListOf<String>()

        if (unusual.first >= 75) {
            reasons +=
                "ورود غیرعادی"
        }

        if (unusual.second >= 75) {
            reasons +=
                "خروج غیرعادی"
        }

        if (currentNet > 0) {
            reasons +=
                "خالص جریان مثبت"
        } else if (currentNet < 0) {
            reasons +=
                "خالص جریان منفی"
        }

        result[definition.key] =
            MoneyFlowPeriod(
                key = definition.key,
                title = definition.title,
                inflowUsd =
                    currentFlow.inflow,
                outflowUsd =
                    currentFlow.outflow,
                netFlowUsd =
                    currentNet,
                inflowChangePct =
                    percentageChange(
                        currentFlow.inflow,
                        previousFlow.inflow
                    ),
                outflowChangePct =
                    percentageChange(
                        currentFlow.outflow,
                        previousFlow.outflow
                    ),
                netChangePct =
                    percentageChange(
                        abs(currentNet),
                        abs(previousNet)
                    ),
                unusualInflow =
                    unusual.first,
                unusualOutflow =
                    unusual.second,
                confidence =
                    (
                        45 +
                            if (
                                previous.isNotEmpty()
                            ) 20 else 0 +
                            if (
                                definition.days >= 30
                            ) 10 else 0 +
                            if (
                                definition.days >= 90
                            ) 5 else 0
                        ).coerceIn(
                            0,
                            95
                        ),
                status = status,
                reasons = reasons
            )
    }

    return result
}

private fun directionalFlow(
    candles: List<Candle>
): DirectionalFlow {

    var inflow = 0.0
    var outflow = 0.0

    candles.forEach { c ->

        val value =
            effectiveQuoteVolume(c)

        if (value <= 0) {
            return@forEach
        }

        val range =
            (
                c.high -
                    c.low
                ).coerceAtLeast(1e-9)

        val multiplier =
            (
                (
                    (c.close - c.low) -
                        (c.high - c.close)
                    ) / range
                ).coerceIn(
                    -1.0,
                    1.0
                )

        inflow +=
            value *
                (0.5 + multiplier * 0.5)

        outflow +=
            value *
                (0.5 - multiplier * 0.5)
    }

    return DirectionalFlow(
        inflow,
        outflow
    )
}

private fun unusualIntensity(
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
        directionalFlow(current)

    val baselineFlows =
        baseline.map {
            directionalFlow(
                listOf(it)
            )
        }

    val inflows =
        baselineFlows.map {
            it.inflow
        }

    val outflows =
        baselineFlows.map {
            it.outflow
        }

    val avgIn =
        inflows.average()
            .coerceAtLeast(1.0)

    val avgOut =
        outflows.average()
            .coerceAtLeast(1.0)

    val dailyIn =
        currentFlow.inflow /
            current.size

    val dailyOut =
        currentFlow.outflow /
            current.size

    val inRatio =
        dailyIn / avgIn

    val outRatio =
        dailyOut / avgOut

    return (
        (
            50 +
                (inRatio - 1) * 35
            )
            .roundToInt()
            .coerceIn(0, 100)
        ) to (
        (
            50 +
                (outRatio - 1) * 35
            )
            .roundToInt()
            .coerceIn(0, 100)
        )
}

private fun status(
    inflow: Double,
    outflow: Double,
    unusualInflow: Int,
    unusualOutflow: Int
): String {

    val total =
        (
            inflow +
                outflow
            ).coerceAtLeast(1.0)

    val ratio =
        (
            inflow -
                outflow
            ) / total

    return when {

        ratio >= 0.12 &&
            unusualInflow >= 75 ->
            "ورود سنگین و غیرعادی"

        ratio <= -0.12 &&
            unusualOutflow >= 75 ->
            "خروج سنگین و غیرعادی"

        ratio >= 0.08 ->
            "ورود پول"

        ratio <= -0.08 ->
            "خروج پول"

        else ->
            "خنثی"
    }
}

private fun percentageChange(
    current: Double,
    previous: Double
): Double {

    if (previous <= 1e-9) {
        return if (
            current > 0
        ) {
            100.0
        } else {
            0.0
        }
    }

    return (
        (
            current - previous
            ) / previous
        ) * 100
}

private fun effectiveQuoteVolume(
    candle: Candle
): Double =
    when {

        candle.quoteVolume > 0 ->
            candle.quoteVolume

        candle.volume > 0 &&
            candle.close > 0 ->
            candle.volume *
                candle.close

        else ->
            0.0
    }

private fun cmf(
    candles: List<Candle>
): Double {

    var money = 0.0
    var volume = 0.0

    candles.forEach { c ->

        val range =
            (
                c.high -
                    c.low
                ).coerceAtLeast(1e-9)

        val multiplier =
            (
                (
                    (c.close - c.low) -
                        (c.high - c.close)
                    ) / range
                )

        val value =
            effectiveQuoteVolume(c)

        money +=
            multiplier * value

        volume += value
    }

    return if (
        volume <= 0
    ) {
        0.0
    } else {
        money / volume
    }
}

private fun obvSlope(
    candles: List<Candle>
): Double {

    if (candles.size < 3) {
        return 0.0
    }

    var obv = 0.0

    val values =
        mutableListOf<Double>()

    for (
        i in 1 until candles.size
    ) {

        val volume =
            effectiveQuoteVolume(
                candles[i]
            )

        obv +=
            when {

                candles[i].close >
                    candles[i - 1].close ->
                    volume

                candles[i].close <
                    candles[i - 1].close ->
                    -volume

                else ->
                    0.0
            }

        values += obv
    }

    val denominator =
        candles.sumOf {
            effectiveQuoteVolume(it)
        }.coerceAtLeast(1.0)

    return (
        (
            values.last() -
                values.first()
            ) / denominator
        ).coerceIn(
            -1.0,
            1.0
        )
}

private fun scoreFrame(
    candles: List<Candle>
): Int {

    if (candles.size < 25) {
        return 50
    }

    val x =
        candles.map {
            it.close
        }

    val last =
        x.last()

    val ma20 =
        x.takeLast(20)
            .average()

    val ma10 =
        x.takeLast(10)
            .average()

    val ret =
        last /
            x[x.size - 21] -
            1

    return (
        50 +
            if (
                last > ma20
            ) 15 else -15 +
            if (
                ma10 > ma20
            ) 15 else -15 +
            (
                ret.coerceIn(
                    -0.15,
                    0.15
                ) * 100
                ).roundToInt()
        ).coerceIn(
            0,
            100
        )
}

private fun resample(
    source: List<Candle>,
    days: Int
): List<Candle> {

    if (days == 1) {
        return source
    }

    val result =
        mutableListOf<Candle>()

    var index = 0

    while (
        index < source.size
    ) {

        val end =
            min(
                index + days,
                source.size
            )

        val group =
            source.subList(
                index,
                end
            )

        result += Candle(
            close =
                group.last().close,
            high =
                group.maxOf {
                    it.high
                },
            low =
                group.minOf {
                    it.low
                },
            volume =
                group.sumOf {
                    it.volume
                },
            quoteVolume =
                group.sumOf {
                    effectiveQuoteVolume(it)
                },
            openTime =
                group.first().openTime
        )

        index += days
    }

    return result
}

private fun analyzeStructure(
    candles: List<Candle>
): StructureResult {

    if (candles.size < 40) {

        return StructureResult(
            50,
            "NEUTRAL",
            false,
            false,
            candles.minOf {
                it.low
            },
            candles.maxOf {
                it.high
            },
            listOf(
                "داده ساختار کافی نیست"
            )
        )
    }

    val recent =
        candles.takeLast(40)

    val support =
        recent
            .dropLast(3)
            .takeLast(20)
            .minOf {
                it.low
            }

    val resistance =
        recent
            .dropLast(3)
            .takeLast(20)
            .maxOf {
                it.high
            }

    val last =
        recent.last().close

    val atr =
        atr(
            candles,
            14
        )

    val breakout =
        last >
            resistance +
                atr * 0.15

    val breakdown =
        last <
            support -
                atr * 0.15

    val score =
        when {

            breakout ->
                88

            breakdown ->
                18

            last >
                resistance * 0.985 ->
                72

            last <
                support * 1.015 ->
                28

            else ->
                50
        }

    val label =
        when {

            breakout ->
                "BREAKOUT"

            breakdown ->
                "BREAKDOWN"

            else ->
                "RANGE"
        }

    val reasons =
        if (breakout) {

            listOf(
                "شکست مقاومت"
            )

        } else if (breakdown) {

            listOf(
                "شکست حمایت"
            )

        } else {

            listOf(
                "قیمت داخل محدوده ساختار است"
            )
        }

    return StructureResult(
        score,
        label,
        breakout,
        breakdown,
        support,
        resistance,
        reasons
    )
}

private fun analyzeDivergence(
    candles: List<Candle>
): DivergenceResult {

    if (candles.size < 50) {

        return DivergenceResult(
            50,
            "NONE",
            false,
            false,
            emptyList()
        )
    }

    val data =
        candles.takeLast(35)

    val half =
        data.size / 2

    val p1 =
        data.take(half)
            .map {
                it.close
            }
            .average()

    val p2 =
        data.takeLast(half)
            .map {
                it.close
            }
            .average()

    val r1 =
        rsiSeries(
            data.take(half)
        ).average()

    val r2 =
        rsiSeries(
            data.takeLast(half)
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
    candles: List<Candle>
): List<Double> {

    val x =
        candles.map {
            it.close
        }

    if (x.size < 16) {
        return listOf(50.0)
    }

    val result =
        mutableListOf<Double>()

    for (
        i in 15 until x.size
    ) {

        var gain = 0.0
        var loss = 0.0

        for (
            j in i - 13..i
        ) {

            val d =
                x[j] -
                    x[j - 1]

            if (d >= 0) {
                gain += d
            } else {
                loss -= d
            }
        }

        result +=
            if (loss == 0.0) {

                100.0

            } else {

                val rs =
                    (gain / 14) /
                        (loss / 14)

                100 -
                    100 / (1 + rs)
            }
    }

    return result
}

private fun analyzeBtcRegime(
    candles: List<Candle>
): BtcRegime {

    if (candles.size < 60) {

        return BtcRegime(
            50,
            "UNKNOWN",
            listOf(
                "داده BTC کافی نیست"
            )
        )
    }

    val x =
        candles.map {
            it.close
        }

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

    val reasons =
        mutableListOf<String>()

    if (last > e20) {
        score += 10
        reasons +=
            "BTC بالای EMA20"
    } else {
        score -= 10
    }

    if (e20 > e50) {
        score += 12
        reasons +=
            "BTC روند میان‌مدت صعودی"
    } else {
        score -= 12
    }

    if (ret > 0.05) {
        score += 10
        reasons +=
            "مومنتوم BTC مثبت"
    } else if (ret < -0.05) {
        score -= 10
        reasons +=
            "مومنتوم BTC منفی"
    }

    val label =
        when {

            score >= 68 ->
                "RISK-ON"

            score <= 32 ->
                "RISK-OFF"

            else ->
                "NEUTRAL"
        }

    return BtcRegime(
        score.coerceIn(
            0,
            100
        ),
        label,
        reasons
    )
}

/*
 * محدوده ورود جدید:
 *
 * قبلاً محدوده ورود به support/resistance وابسته بود و ممکن بود
 * چند درصد از قیمت فعلی فاصله بگیرد.
 *
 * حالا مرکز محدوده روی آخرین قیمت کندل قرار می‌گیرد.
 * دامنه با نوسان کوتاه‌مدت محاسبه می‌شود و بین 0.15٪ تا 0.80٪
 * قیمت محدود می‌شود.
 *
 * بنابراین:
 * BUY  -> کمی پایین‌تر از قیمت فعلی تا کمی بالاتر
 * SELL -> کمی پایین‌تر تا کمی بالاتر از قیمت فعلی
 *
 * حد ضرر همچنان ساختاری است تا تغییر محدوده ورود باعث حذف
 * محافظت از معامله نشود.
 */
private fun buildTradePlan(
    candles: List<Candle>,
    structure: StructureResult,
    signal: String
): TradePlan? {

    if (
        signal != "BUY" &&
        signal != "STRONG BUY" &&
        signal != "SELL" &&
        signal != "STRONG SELL"
    ) {
        return null
    }

    val last =
        candles.last().close

    if (
        !last.isFinite() ||
        last <= 0
    ) {
        return null
    }

    val atrValue =
        atr(
            candles,
            14
        )

    if (
        !atrValue.isFinite() ||
        atrValue <= 0
    ) {
        return null
    }

    /*
     * نوسان کوتاه‌مدت‌تر:
     * به جای استفاده مستقیم از ATR کامل برای محدوده ورود،
     * میانگین دامنه 7 کندل اخیر را نیز در نظر می‌گیریم.
     */
    val recentCandles =
        candles.takeLast(
            minOf(
                7,
                candles.size
            )
        )

    val recentRange =
        if (recentCandles.isNotEmpty()) {
            recentCandles
                .map {
                    (it.high - it.low)
                        .coerceAtLeast(0.0)
                }
                .average()
        } else {
            atrValue
        }

    /*
     * دامنه ورود:
     * - حداقل 0.15٪ قیمت
     * - حداکثر 0.80٪ قیمت
     * - ترکیبی از ATR و نوسان 7 کندل اخیر
     */
    val minimumBand =
        last * 0.0015

    val maximumBand =
        last * 0.008

    val volatilityBand =
        max(
            atrValue * 0.20,
            recentRange * 0.35
        )

    val band =
        volatilityBand
            .coerceIn(
                minimumBand,
                maximumBand
            )

    if (
        signal == "BUY" ||
        signal == "STRONG BUY"
    ) {

        /*
         * محدوده خرید اطراف قیمت فعلی:
         * 65٪ دامنه پایین قیمت
         * 35٪ دامنه بالای قیمت
         */
        val entryLow =
            last - band * 0.65

        val entryHigh =
            last + band * 0.35

        /*
         * برای حد ضرر ابتدا فاصله منطقی از قیمت فعلی
         * تعیین می‌شود و سپس ساختار بازار در آن دخالت می‌کند.
         *
         * اگر support خیلی دور باشد، دیگر اجازه نمی‌دهیم
         * SL صرفاً به خاطر support بیش از حد دور شود.
         */
        val minimumRisk =
            max(
                band * 2.0,
                last * 0.006
            )

        val maximumStructureDistance =
            last * 0.025

        val nearbySupport =
            if (
                structure.support.isFinite() &&
                structure.support > 0 &&
                structure.support < last &&
                last - structure.support <=
                maximumStructureDistance
            ) {
                structure.support
            } else {
                last - maximumStructureDistance
            }

        val structuralStop =
            nearbySupport -
                band * 0.20

        val distanceBasedStop =
            last -
                minimumRisk

        val sl =
            min(
                structuralStop,
                distanceBasedStop
            )

        val risk =
            max(
                last - sl,
                minimumRisk
            )

        val tp1 =
            last +
                risk * 1.5

        val tp2 =
            last +
                risk * 2.5

        return TradePlan(
            entryLow =
                entryLow.coerceAtLeast(
                    last * 0.98
                ),
            entryHigh =
                entryHigh.coerceAtMost(
                    last * 1.02
                ),
            stopLoss =
                sl.coerceAtLeast(
                    last * 0.96
                ),
            tp1 =
                tp1,
            tp2 =
                tp2,
            riskReward =
                2.0
        )
    }

    /*
     * SELL:
     * محدوده فروش نیز اطراف قیمت فعلی است،
     * اما کمی فضای بیشتر بالای قیمت برای ورود در پولبک
     * در نظر گرفته می‌شود.
     */
    val entryLow =
        last - band * 0.35

    val entryHigh =
        last + band * 0.65

    val minimumRisk =
        max(
            band * 2.0,
            last * 0.006
        )

    val maximumStructureDistance =
        last * 0.025

    val nearbyResistance =
        if (
            structure.resistance.isFinite() &&
            structure.resistance > last &&
            structure.resistance - last <=
            maximumStructureDistance
        ) {
            structure.resistance
        } else {
            last + maximumStructureDistance
        }

    val structuralStop =
        nearbyResistance +
            band * 0.20

    val distanceBasedStop =
        last +
            minimumRisk

    val sl =
        max(
            structuralStop,
            distanceBasedStop
        )

    val risk =
        max(
            sl - last,
            minimumRisk
        )

    val tp1 =
        last -
            risk * 1.5

    val tp2 =
        last -
            risk * 2.5

    return TradePlan(
        entryLow =
            entryLow.coerceAtLeast(
                last * 0.98
            ),
        entryHigh =
            entryHigh.coerceAtMost(
                last * 1.02
            ),
        stopLoss =
            sl.coerceAtMost(
                last * 1.04
            ),
        tp1 =
            tp1,
        tp2 =
            tp2,
        riskReward =
            2.0
    )
}

private fun atr(
    candles: List<Candle>,
    period: Int
): Double {

    if (candles.size < 2) {
        return 0.0
    }

    val start =
        maxOf(
            1,
            candles.size - period
        )

    val ranges =
        mutableListOf<Double>()

    for (
        i in start until candles.size
    ) {

        val current =
            candles[i]

        val previous =
            candles[i - 1].close

        ranges +=
            maxOf(
                current.high -
                    current.low,

                abs(
                    current.high -
                        previous
                ),

                abs(
                    current.low -
                        previous
                )
            )
    }

    return ranges
        .average()
        .coerceAtLeast(1e-9)
}

private fun ema(
    values: List<Double>,
    period: Int
): Double {

    val n =
        minOf(
            period,
            values.size
        )

    if (n <= 0) {
        return 0.0
    }

    val k =
        2.0 /
            (n + 1)

    var result =
        values
            .take(n)
            .average()

    for (
        i in n until values.size
    ) {

        result =
            values[i] * k +
                result *
                (1 - k)
    }

    return result
}

}
