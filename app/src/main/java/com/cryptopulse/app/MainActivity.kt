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
import androidx.compose.ui.Alignment
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
    mutableStateOf("آماده تحلیل")
}

fun analyzeCoin() {

    if (loading) return

    scope.launch {

        loading = true
        analysisResult = null
        message = "در حال دریافت اطلاعات $symbol ..."

        try {

            val normalized = normalizeSymbol(symbol)

            val repository = LiveRepository()
            val snapshot = repository.load(normalized)

            message = "داده دریافت شد؛ در حال تحلیل..."

            val newsSnapshot =
                NewsRepository().load(normalized)

            val flow = MarketFlowData(
                openInterest = snapshot.openInterest,
                fundingRate = snapshot.fundingRate,
                openInterestHistory = snapshot.openInterestHistory,
                longShortHistory = snapshot.longShortHistory,
                takerVolumeHistory = snapshot.takerVolumeHistory
            )

            val result = AnalysisEngine.analyze(
                candles = snapshot.candles,
                flow = flow,
                newsScore = newsSnapshot.score,
                newsConfidence = newsSnapshot.confidence,
                btcCandles = snapshot.btcCandles
            )

            analysisResult = result
            message = "تحلیل $normalized کامل شد."

        } catch (e: Exception) {

            analysisResult = null

            message =
                "خطا: ${e.message ?: "خطای نامشخص"}"

        } finally {

            loading = false
        }
    }
}

fun scanMarket() {

    if (loading) return

    scope.launch {

        loading = true
        analysisResult = null
        scanResult = null
        message = "در حال اسکن بازار..."

        try {

            val result =
                MarketScanner().scanDetailed(
                    universeLimit = 1000,
                    technicalLimit = 250,
                    enrichLimit = 100
                )

            scanResult = result

            message =
                "اسکن کامل شد؛ ${result.analyzedCount} ارز تحلیل شدند."

        } catch (e: Exception) {

            message =
                "خطا در اسکن بازار: ${e.message ?: "خطای نامشخص"}"

        } finally {

            loading = false
        }
    }
}

LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {

    item {

        Text(
            text = "CryptoAnalysis",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = "تحلیل هوشمند بازار ارزهای دیجیتال",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = "Technical • Money Flow • News • Structure • BTC Regime",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    item {

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    text = "🔎 سرچ و تحلیل ارز",
                    style = MaterialTheme.typography.titleLarge
                )

                OutlinedTextField(
                    value = symbol,
                    onValueChange = {
                        symbol = it
                            .uppercase(Locale.US)
                            .replace(" ", "")
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        }
    }

    item {

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    text = "وضعیت",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = message
                )

                if (loading) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    analysisResult?.let { result ->

        item {

            FinalResultCard(result)
        }

        item {

            AnalysisSummaryCard(result)
        }

        item {

            MoneyFlowCard(result)
        }

        item {

            TradePlanCard(result)
        }
    }

    scanResult?.let { scan ->

        item {

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {

                    Text(
                        text = "🌐 نتیجه نهایی اسکن بازار",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = "بازار بررسی‌شده: ${scan.universeCount}"
                    )

                    Text(
                        text = "ارزهای تحلیل‌شده: ${scan.analyzedCount}"
                    )
                }
            }
        }

        item {

            Text(
                text = "🔥 Top 10 کل بازار",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (scan.top10All.isEmpty()) {

            item {

                Text(
                    text = "هیچ ارزی با موفقیت تحلیل نشد."
                )
            }

        } else {

            items(scan.top10All) { candidate ->

                CandidateCard(candidate)
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "🏆 Top 10 رتبه‌های 1 تا 100",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (scan.top10Top100.isEmpty()) {

            item {

                Text(
                    text = "برای این بخش نتیجه‌ای وجود ندارد."
                )
            }

        } else {

            items(scan.top10Top100) { candidate ->

                CandidateCard(candidate)
            }
        }
    }

    item {

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text = "⚠️ نتایج تحلیل آماری هستند و تضمین سود یا پیش‌بینی قطعی قیمت نیستند.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

}

@Composable
private fun FinalResultCard(
result: AnalysisResult
) {

val signal = finalSignal(result)

Card(
    modifier = Modifier.fillMaxWidth()
) {

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Text(
            text = "🎯 تحلیل نهایی",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = signal,
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Score: ${result.score}/100"
        )

        Text(
            text = "Confidence: ${result.confidence}%"
        )

        Text(
            text = "Pump Probability: ${result.pump}%"
        )

        Text(
            text = "Dump Probability: ${result.dump}%"
        )

        Text(
            text = "Money Flow: ${result.moneyFlow}/100"
        )

        Text(
            text = "News Score: ${result.news}/100"
        )

        Text(
            text = "Trend: ${result.trend}/100"
        )

        if (result.reasons.isNotEmpty()) {

            Text(
                text = "دلایل اصلی",
                style = MaterialTheme.typography.titleMedium
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
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {

        Text(
            text = "📊 جزئیات تحلیل",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Structure: ${result.structure.label}"
        )

        Text(
            text = "Divergence: ${result.divergence.label}"
        )

        Text(
            text = "BTC Regime: ${result.btcRegime.label}"
        )

        Text(
            text = "News: ${result.news}/100"
        )

        if (result.timeframeScores.isNotEmpty()) {

            Text(
                text = "امتیاز تایم‌فریم‌ها",
                style = MaterialTheme.typography.titleMedium
            )

            result.timeframeScores.forEach { entry ->

                Text(
                    text = "${entry.key}: ${entry.value}"
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

val flow = result.moneyFlowDetails

Card(
    modifier = Modifier.fillMaxWidth()
) {

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {

        Text(
            text = "💰 Money Flow",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "وضعیت: ${flow.label}"
        )

        Text(
            text = "Score: ${flow.score}/100"
        )

        Text(
            text = "Confidence: ${flow.confidence}%"
        )

        flow.reasons
            .take(8)
            .forEach { reason ->

                Text(
                    text = "• $reason"
                )
            }

        if (flow.periods.isNotEmpty()) {

            Text(
                text = "جریان پول در تایم‌فریم‌ها",
                style = MaterialTheme.typography.titleMedium
            )

            flow.periods.forEach { entry ->

                val period = entry.value

                Text(
                    text = "${period.title}: ${period.status}"
                )

                Text(
                    text = "ورود: ${formatMoney(period.inflowUsd)}"
                )

                Text(
                    text = "خروج: ${formatMoney(period.outflowUsd)}"
                )

                Text(
                    text = "Net: ${formatMoney(period.netFlowUsd)}"
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

val plan = result.tradePlan ?: return

Card(
    modifier = Modifier.fillMaxWidth()
) {

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {

        Text(
            text = "🎯 Trade Plan",
            style = MaterialTheme.typography.titleLarge
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

val result = candidate.result

Card(
    modifier = Modifier.fillMaxWidth()
) {

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Text(
                text = candidate.symbol,
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = finalSignal(result),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Text(
            text = "Score: ${result.score}/100"
        )

        Text(
            text = "Confidence: ${result.confidence}%"
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

        result.reasons
            .firstOrNull()
            ?.let { reason ->

                Text(
                    text = "• $reason"
                )
            }
    }
}

}

private fun finalSignal(
result: AnalysisResult
): String {

val score = result.score.coerceIn(0, 100)
val confidence = result.confidence.coerceIn(0, 100)
val pump = result.pump.coerceIn(0, 100)
val dump = result.dump.coerceIn(0, 100)

val heavyInflow =
    isHeavyInflow(result.moneyFlowDetails.label)

val heavyOutflow =
    isHeavyOutflow(result.moneyFlowDetails.label)

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

val absolute = abs(value)

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
