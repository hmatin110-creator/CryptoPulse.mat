package com.cryptopulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    CryptoAnalysisScreen()
                }
            }
        }
    }
}

@Composable
private fun CryptoAnalysisScreen() {

    val scope = rememberCoroutineScope()

    var symbol by remember {
        mutableStateOf("BTCUSDT")
    }

    var analysisResult by remember {
        mutableStateOf<AnalysisResult?>(null)
    }

    var scanResult by remember {
        mutableStateOf<MarketScanResult?>(null)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("")
    }

    fun analyzeCoin() {

        scope.launch {

            loading = true
            message = "در حال تحلیل $symbol ..."

            runCatching {

                val normalized =
                    normalizeSymbol(symbol)

                val repository =
                    LiveRepository()

                val snapshot =
                    repository.load(normalized)

                val newsSnapshot =
                    NewsRepository().load(normalized)

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

                AnalysisEngine.analyze(
                    candles = snapshot.candles,
                    flow = flow,
                    newsScore = newsSnapshot.score,
                    newsConfidence =
                        newsSnapshot.confidence,
                    btcCandles =
                        snapshot.btcCandles
                )

            }.onSuccess { result ->

                analysisResult = result
                message = "تحلیل کامل شد."

            }.onFailure { error ->

                analysisResult = null

                message =
                    "خطا در تحلیل: ${error.message ?: "خطای نامشخص"}"
            }

            loading = false
        }
    }

    fun scanMarket() {

        scope.launch {

            loading = true
            message =
                "در حال اسکن کل بازار..."

            runCatching {

                MarketScanner().scanDetailed(
                    universeLimit = 1000,
                    technicalLimit = 250,
                    enrichLimit = 100
                )

            }.onSuccess { result ->

                scanResult = result

                message =
                    "اسکن کامل شد. ${result.analyzedCount} ارز تحلیل شدند."

            }.onFailure { error ->

                scanResult = null

                message =
                    "خطا در اسکن بازار: ${error.message ?: "خطای نامشخص"}"
            }

            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        item {

            Text(
                text = "CryptoAnalysis",
                style =
                    MaterialTheme.typography.headlineMedium
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text =
                    "تحلیل تکنیکال + Money Flow + News + Market Structure",
                style =
                    MaterialTheme.typography.bodyMedium
            )
        }

        item {

            OutlinedTextField(
                value = symbol,
                onValueChange = {
                    symbol =
                        it.uppercase(Locale.US)
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("نماد ارز")
                },
                placeholder = {
                    Text("مثلاً BTCUSDT")
                },
                singleLine = true
            )
        }

        item {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                Button(
                    onClick = {
                        analyzeCoin()
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("تحلیل ارز")
                }

                OutlinedButton(
                    onClick = {
                        scanMarket()
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("اسکن بازار")
                }
            }
        }

        if (loading) {

            item {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.Center
                ) {

                    CircularProgressIndicator()
                }
            }
        }

        if (message.isNotBlank()) {

            item {

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Text(
                        text = message,
                        modifier =
                            Modifier.padding(12.dp)
                    )
                }
            }
        }

        analysisResult?.let { result ->

            item {

                FinalResultCard(
                    result = result
                )
            }

            item {

                AnalysisSummaryCard(
                    result = result
                )
            }

            item {

                MoneyFlowCard(
                    result = result
                )
            }

            item {

                TradePlanCard(
                    result = result
                )
            }
        }

        scanResult?.let { scan ->

            item {

                Text(
                    text = "نتیجه نهایی اسکن بازار",
                    style =
                        MaterialTheme.typography.headlineSmall
                )
            }

            item {

                Text(
                    text =
                        "بازار بررسی‌شده: ${scan.universeCount}\n" +
                        "ارزهای تحلیل‌شده: ${scan.analyzedCount}"
                )
            }

            item {

                Text(
                    text = "🔥 Top 10 کل بازار",
                    style =
                        MaterialTheme.typography.titleLarge
                )
            }

            items(scan.top10All) { candidate ->

                CandidateCard(
                    candidate = candidate
                )
            }

            item {

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                Text(
                    text =
                        "🏆 Top 10 بین رتبه‌های 1 تا 100",
                    style =
                        MaterialTheme.typography.titleLarge
                )
            }

            items(scan.top10Top100) { candidate ->

                CandidateCard(
                    candidate = candidate
                )
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Text(
                text =
                    "هشدار: نتایج تحلیل آماری هستند و تضمین سود یا پیش‌بینی قطعی قیمت نیستند.",
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FinalResultCard(
    result: AnalysisResult
) {

    val signal =
        finalSignal(result)

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = "🎯 نتیجه نهایی",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text = signal,
                style =
                    MaterialTheme.typography.headlineMedium
            )

            Text(
                text =
                    "Score: ${result.score}/100"
            )

            Text(
                text =
                    "Confidence: ${result.confidence}%"
            )

            Text(
                text =
                    "Pump Probability: ${result.pump}%"
            )

            Text(
                text =
                    "Dump Probability: ${result.dump}%"
            )

            Text(
                text =
                    "Money Flow: ${result.moneyFlow}/100"
            )

            Text(
                text =
                    "News Score: ${result.news}/100"
            )

            Text(
                text =
                    "Trend: ${result.trend}/100"
            )

            if (result.reasons.isNotEmpty()) {

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text = "دلایل اصلی",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                result.reasons
                    .take(8)
                    .forEach { reason ->

                        Text(
                            text = "• $reason"
                        )
                    }
            }
        }
    }
}

@Composable
private fun AnalysisSummaryCard(
    result: AnalysisResult
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = "جزئیات تحلیل",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text =
                    "Structure: ${result.structure.label}"
            )

            Text(
                text =
                    "Divergence: ${result.divergence.label}"
            )

            Text(
                text =
                    "BTC Regime: ${result.btcRegime.label}"
            )

            Text(
                text =
                    "News: ${result.news}/100"
            )

            if (result.timeframeScores.isNotEmpty()) {

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text = "امتیاز تایم‌فریم‌ها",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                result.timeframeScores
                    .forEach { entry ->

                        Text(
                            text =
                                "${entry.key}: ${entry.value}"
                        )
                    }
            }
        }
    }
}

@Composable
private fun MoneyFlowCard(
    result: AnalysisResult
) {

    val flow =
        result.moneyFlowDetails

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(7.dp)
        ) {

            Text(
                text = "💰 Money Flow",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text =
                    "وضعیت: ${flow.label}"
            )

            Text(
                text =
                    "Score: ${flow.score}/100"
            )

            Text(
                text =
                    "Confidence: ${flow.confidence}%"
            )

            if (flow.reasons.isNotEmpty()) {

                flow.reasons
                    .take(8)
                    .forEach { reason ->

                        Text(
                            text = "• $reason"
                        )
                    }
            }

            if (flow.periods.isNotEmpty()) {

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text = "جریان پول در تایم‌فریم‌ها",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                flow.periods
                    .forEach { entry ->

                        val period =
                            entry.value

                        Text(
                            text =
                                "${period.title}: ${period.status}"
                        )

                        Text(
                            text =
                                "ورود: ${formatMoney(period.inflowUsd)}"
                        )

                        Text(
                            text =
                                "خروج: ${formatMoney(period.outflowUsd)}"
                        )

                        Text(
                            text =
                                "Net: ${formatMoney(period.netFlowUsd)}"
                        )
                    }
            }
        }
    }
}

@Composable
private fun TradePlanCard(
    result: AnalysisResult
) {

    val plan =
        result.tradePlan
            ?: return

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = "📌 Trade Plan",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text =
                    "Entry: ${formatPrice(plan.entryLow)} - ${formatPrice(plan.entryHigh)}"
            )

            Text(
                text =
                    "Stop Loss: ${formatPrice(plan.stopLoss)}"
            )

            Text(
                text =
                    "TP1: ${formatPrice(plan.tp1)}"
            )

            Text(
                text =
                    "TP2: ${formatPrice(plan.tp2)}"
            )

            Text(
                text =
                    "Risk / Reward: ${formatRR(plan.riskReward)}"
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: ScanCandidate
) {

    val result =
        candidate.result

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                Text(
                    text = candidate.symbol,
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Text(
                    text = finalSignal(result),
                    style =
                        MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text =
                    "Score: ${result.score}/100"
            )

            Text(
                text =
                    "Confidence: ${result.confidence}%"
            )

            Text(
                text =
                    "Pump: ${result.pump}% | Dump: ${result.dump}%"
            )

            Text(
                text =
                    "Money Flow: ${result.moneyFlowDetails.label}"
            )

            Text(
                text =
                    "News: ${result.news}/100"
            )

            Text(
                text =
                    "Structure: ${result.structure.label}"
            )

            result.tradePlan?.let { plan ->

                Text(
                    text =
                        "Entry: ${formatPrice(plan.entryLow)} - ${formatPrice(plan.entryHigh)}"
                )

                Text(
                    text =
                        "SL: ${formatPrice(plan.stopLoss)}"
                )

                Text(
                    text =
                        "TP1: ${formatPrice(plan.tp1)}"
                )

                Text(
                    text =
                        "TP2: ${formatPrice(plan.tp2)}"
                )
            }

            if (result.reasons.isNotEmpty()) {

                Text(
                    text =
                        "• ${result.reasons.first()}"
                )
            }
        }
    }
}

private fun finalSignal(
    result: AnalysisResult
): String {

    val score =
        result.score.coerceIn(0, 100)

    val confidence =
        result.confidence.coerceIn(0, 100)

    val pump =
        result.pump.coerceIn(0, 100)

    val dump =
        result.dump.coerceIn(0, 100)

    val heavyInflow =
        isHeavyInflow(
            result.moneyFlowDetails.label
        )

    val heavyOutflow =
        isHeavyOutflow(
            result.moneyFlowDetails.label
        )

    if (
        score >= 82 &&
        confidence >= 75 &&
        pump >= 75 &&
        heavyInflow &&
        result.news >= 45 &&
        !heavyOutflow
    ) {
        return "🟢 STRONG BUY"
    }

    if (
        score <= 25 &&
        confidence >= 75 &&
        dump >= 75 &&
        heavyOutflow &&
        result.news <= 55 &&
        !heavyInflow
    ) {
        return "🔴 STRONG SELL"
    }

    if (
        score >= 70 &&
        confidence >= 60 &&
        pump >= 65 &&
        !heavyOutflow
    ) {
        return "🟢 BUY"
    }

    if (
        score <= 35 &&
        confidence >= 60 &&
        dump >= 65 &&
        !heavyInflow
    ) {
        return "🔴 SELL"
    }

    return "🟡 HOLD / WAIT"
}

private fun isHeavyInflow(
    label: String
): Boolean {

    val normalized =
        label
            .trim()
            .uppercase(Locale.US)

    return normalized.contains("HEAVY INFLOW") ||
        normalized.contains("INFLOW HEAVY") ||
        label.contains("ورود سنگین") ||
        label.contains("ورود غیرعادی")
}

private fun isHeavyOutflow(
    label: String
): Boolean {

    val normalized =
        label
            .trim()
            .uppercase(Locale.US)

    return normalized.contains("HEAVY OUTFLOW") ||
        normalized.contains("OUTFLOW HEAVY") ||
        label.contains("خروج سنگین") ||
        label.contains("خروج غیرعادی")
}

private fun normalizeSymbol(
    value: String
): String {

    var s =
        value
            .trim()
            .uppercase(Locale.US)
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")

    if (s.isBlank()) {
        s = "BTCUSDT"
    }

    if (!s.endsWith("USDT")) {
        s += "USDT"
    }

    return s
}

private fun formatMoney(
    value: Double
): String {

    val absolute =
        abs(value)

    return when {

        absolute >= 1_000_000_000.0 ->
            "$" + String.format(
                Locale.US,
                "%.2fB",
                value / 1_000_000_000.0
            )

        absolute >= 1_000_000.0 ->
            "$" + String.format(
                Locale.US,
                "%.2fM",
                value / 1_000_000.0
            )

        absolute >= 1_000.0 ->
            "$" + String.format(
                Locale.US,
                "%.2fK",
                value / 1_000.0
            )

        else ->
            "$" + String.format(
                Locale.US,
                "%.2f",
                value
            )
    }
}

private fun formatPrice(
    value: Double
): String {

    return when {

        value >= 1000.0 ->
            String.format(
                Locale.US,
                "%.2f",
                value
            )

        value >= 1.0 ->
            String.format(
                Locale.US,
                "%.4f",
                value
            )

        else ->
            String.format(
                Locale.US,
                "%.8f",
                value
            )
    }
}

private fun formatRR(
    value: Double
): String {

    return String.format(
        Locale.US,
        "%.2f",
        value
    )
}
