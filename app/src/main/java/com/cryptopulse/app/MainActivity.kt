package com.cryptopulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
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
            scanResult = null

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

                message =
                    "تحلیل $normalized کامل شد."

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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {

            HeaderSection()
        }

        item {

            SearchSection(
                symbol = symbol,
                loading = loading,
                onSymbolChange = {
                    symbol = it
                        .uppercase(Locale.US)
                        .replace(" ", "")
                },
                onAnalyze = {
                    analyzeCoin()
                },
                onScan = {
                    scanMarket()
                }
            )
        }

        item {

            StatusSection(
                message = message,
                loading = loading
            )
        }

        analysisResult?.let { result ->

            item {

                FinalResultTable(result)
            }

            item {

                AnalysisTable(result)
            }

            item {

                MoneyFlowTable(result)
            }

            item {

                TradePlanTable(result)
            }

            item {

                ReasonsTable(result)
            }
        }

        scanResult?.let { scan ->

            item {

                MarketSummaryTable(scan)
            }

            item {

                SectionTitle("🔥 Top 10 کل بازار")
            }

            if (scan.top10All.isEmpty()) {

                item {

                    EmptyCard("هیچ ارزی با موفقیت تحلیل نشد.")
                }

            } else {

                item {

                    CandidateTable(
                        candidates = scan.top10All
                    )
                }
            }

            item {

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                SectionTitle(
                    "🏆 Top 10 رتبه‌های 1 تا 100"
                )
            }

            if (scan.top10Top100.isEmpty()) {

                item {

                    EmptyCard(
                        "برای این بخش نتیجه‌ای وجود ندارد."
                    )
                }

            } else {

                item {

                    CandidateTable(
                        candidates = scan.top10Top100
                    )
                }
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text =
                    "⚠️ نتایج تحلیل آماری هستند و تضمین سود یا پیش‌بینی قطعی قیمت نیستند.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HeaderSection() {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Text(
                text = "CryptoAnalysis",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "تحلیل هوشمند بازار ارزهای دیجیتال",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text =
                    "Technical • Money Flow • News • Structure • BTC Regime",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SearchSection(
    symbol: String,
    loading: Boolean,
    onSymbolChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onScan: () -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Text(
                text = "🔎 تحلیل ارز",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = symbol,
                onValueChange = onSymbolChange,
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
                    onClick = onAnalyze,
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) {

                    Text("تحلیل ارز")
                }

                OutlinedButton(
                    onClick = onScan,
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) {

                    Text("اسکن بازار")
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    message: String,
    loading: Boolean
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (loading) {

                CircularProgressIndicator(
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                )
            }

            Column {

                Text(
                    text = "وضعیت",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(text = message)
            }
        }
    }
}

@Composable
private fun FinalResultTable(
    result: AnalysisResult
) {

    val signal = finalSignal(result)

    TableCard(
        title = "🎯 تحلیل نهایی"
    ) {

        TableHeader(
            "شاخص",
            "مقدار"
        )

        TableRow(
            "پیشنهاد نهایی",
            signal,
            boldValue = true
        )

        TableRow(
            "Score",
            "${result.score}/100"
        )

        TableRow(
            "Confidence",
            "${result.confidence}%"
        )

        TableRow(
            "Pump Probability",
            "${result.pump}%"
        )

        TableRow(
            "Dump Probability",
            "${result.dump}%"
        )

        TableRow(
            "Money Flow",
            "${result.moneyFlow}/100"
        )

        TableRow(
            "News Score",
            "${result.news}/100"
        )

        TableRow(
            "Trend",
            "${result.trend}/100"
        )
    }
}

@Composable
private fun AnalysisTable(
    result: AnalysisResult
) {

    TableCard(
        title = "📊 جدول تحلیل بازار"
    ) {

        TableHeader(
            "بخش",
            "نتیجه"
        )

        TableRow(
            "ساختار بازار",
            result.structure.label
        )

        TableRow(
            "Breakout",
            if (result.structure.breakout) "بله" else "خیر"
        )

        TableRow(
            "Breakdown",
            if (result.structure.breakdown) "بله" else "خیر"
        )

        TableRow(
            "حمایت",
            formatPrice(result.structure.support)
        )

        TableRow(
            "مقاومت",
            formatPrice(result.structure.resistance)
        )

        TableRow(
            "واگرایی",
            result.divergence.label
        )

        TableRow(
            "رژیم BTC",
            result.btcRegime.label
        )

        TableRow(
            "امتیاز اخبار",
            "${result.news}/100"
        )

        TableRow(
            "اطمینان تحلیل",
            "${result.confidence}%"
        )

        if (result.timeframeScores.isNotEmpty()) {

            result.timeframeScores.forEach { entry ->

                TableRow(
                    "تایم‌فریم ${entry.key}",
                    "${entry.value}/100"
                )
            }
        }
    }
}

@Composable
private fun MoneyFlowTable(
    result: AnalysisResult
) {

    val flow = result.moneyFlowDetails

    TableCard(
        title = "💰 جریان پول"
    ) {

        TableHeader(
            "شاخص",
            "مقدار"
        )

        TableRow(
            "وضعیت کلی",
            flow.label,
            boldValue = true
        )

        TableRow(
            "Money Flow Score",
            "${flow.score}/100"
        )

        TableRow(
            "Confidence",
            "${flow.confidence}%"
        )

        if (flow.periods.isEmpty()) {

            TableRow(
                "دوره‌ها",
                "داده‌ای موجود نیست"
            )

        } else {

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            FlowPeriodHeader()

            flow.periods.forEach { entry ->

                FlowPeriodRow(
                    period = entry.value
                )
            }
        }
    }
}

@Composable
private fun FlowPeriodHeader() {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(
                rememberScrollState()
            )
    ) {

        TableCell(
            text = "دوره",
            width = 75.dp,
            bold = true
        )

        TableCell(
            text = "ورود",
            width = 90.dp,
            bold = true
        )

        TableCell(
            text = "ورود سنگین",
            width = 90.dp,
            bold = true
        )

        TableCell(
            text = "خروج",
            width = 90.dp,
            bold = true
        )

        TableCell(
            text = "خروج سنگین",
            width = 90.dp,
            bold = true
        )

        TableCell(
            text = "Net",
            width = 90.dp,
            bold = true
        )

        TableCell(
            text = "وضعیت",
            width = 110.dp,
            bold = true
        )
    }
}

@Composable
private fun FlowPeriodRow(
    period: MoneyFlowPeriod
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(
                rememberScrollState()
            )
    ) {

        TableCell(
            text = period.title,
            width = 75.dp
        )

        TableCell(
            text = formatMoney(period.inflowUsd),
            width = 90.dp
        )

        TableCell(
            text =
                if (period.unusualInflow > 0) {
                    "بله (${period.unusualInflow})"
                } else {
                    "خیر"
                },
            width = 90.dp
        )

        TableCell(
            text = formatMoney(period.outflowUsd),
            width = 90.dp
        )

        TableCell(
            text =
                if (period.unusualOutflow > 0) {
                    "بله (${period.unusualOutflow})"
                } else {
                    "خیر"
                },
            width = 90.dp
        )

        TableCell(
            text = formatMoney(period.netFlowUsd),
            width = 90.dp
        )

        TableCell(
            text = period.status,
            width = 110.dp
        )
    }
}

@Composable
private fun TradePlanTable(
    result: AnalysisResult
) {

    val plan = result.tradePlan

    TableCard(
        title = "🎯 برنامه معامله"
    ) {

        if (plan == null) {

            TableRow(
                "وضعیت",
                "برای این سیگنال برنامه ورود فعال نیست."
            )

        } else {

            TableHeader(
                "مورد",
                "مقدار"
            )

            TableRow(
                "محدوده ورود",
                "${formatPrice(plan.entryLow)} - ${formatPrice(plan.entryHigh)}"
            )

            TableRow(
                "Stop Loss",
                formatPrice(plan.stopLoss)
            )

            TableRow(
                "TP1",
                formatPrice(plan.tp1)
            )

            TableRow(
                "TP2",
                formatPrice(plan.tp2)
            )

            TableRow(
                "Risk / Reward",
                formatRR(plan.riskReward)
            )
        }
    }
}

@Composable
private fun ReasonsTable(
    result: AnalysisResult
) {

    if (result.reasons.isEmpty()) {
        return
    }

    TableCard(
        title = "🧠 دلایل تحلیل"
    ) {

        result.reasons
            .take(10)
            .forEachIndexed { index, reason ->

                TableRow(
                    "${index + 1}",
                    reason
                )
            }
    }
}

@Composable
private fun MarketSummaryTable(
    scan: MarketScanResult
) {

    TableCard(
        title = "🌐 خلاصه اسکن بازار"
    ) {

        TableHeader(
            "شاخص",
            "مقدار"
        )

        TableRow(
            "کل بازار بررسی‌شده",
            scan.universeCount.toString()
        )

        TableRow(
            "ارزهای تحلیل‌شده",
            scan.analyzedCount.toString()
        )

        TableRow(
            "Top 10 کل بازار",
            scan.top10All.size.toString()
        )

        TableRow(
            "Top 10 رتبه 1 تا 100",
            scan.top10Top100.size.toString()
        )
    }
}

@Composable
private fun CandidateTable(
    candidates: List<ScanCandidate>
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(8.dp)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(
                        rememberScrollState()
                    )
            ) {

                TableCell(
                    text = "ارز",
                    width = 105.dp,
                    bold = true
                )

                TableCell(
                    text = "پیشنهاد",
                    width = 125.dp,
                    bold = true
                )

                TableCell(
                    text = "Score",
                    width = 70.dp,
                    bold = true
                )

                TableCell(
                    text = "Confidence",
                    width = 90.dp,
                    bold = true
                )

                TableCell(
                    text = "Pump",
                    width = 70.dp,
                    bold = true
                )

                TableCell(
                    text = "Dump",
                    width = 70.dp,
                    bold = true
                )

                TableCell(
                    text = "Money Flow",
                    width = 110.dp,
                    bold = true
                )

                TableCell(
                    text = "News",
                    width = 70.dp,
                    bold = true
                )

                TableCell(
                    text = "Structure",
                    width = 110.dp,
                    bold = true
                )
            }

            candidates.forEach { candidate ->

                val result = candidate.result

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        )
                ) {

                    TableCell(
                        text = candidate.symbol,
                        width = 105.dp,
                        bold = true
                    )

                    TableCell(
                        text = finalSignal(result),
                        width = 125.dp
                    )

                    TableCell(
                        text = "${result.score}",
                        width = 70.dp
                    )

                    TableCell(
                        text = "${result.confidence}%",
                        width = 90.dp
                    )

                    TableCell(
                        text = "${result.pump}%",
                        width = 70.dp
                    )

                    TableCell(
                        text = "${result.dump}%",
                        width = 70.dp
                    )

                    TableCell(
                        text = result.moneyFlowDetails.label,
                        width = 110.dp
                    )

                    TableCell(
                        text = "${result.news}",
                        width = 70.dp
                    )

                    TableCell(
                        text = result.structure.label,
                        width = 110.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCard(
    title: String,
    content: @Composable () -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    start = 8.dp,
                    top = 6.dp,
                    bottom = 8.dp
                )
            )

            content()
        }
    }
}

@Composable
private fun TableHeader(
    first: String,
    second: String
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(
                rememberScrollState()
            )
    ) {

        TableCell(
            text = first,
            width = 145.dp,
            bold = true
        )

        TableCell(
            text = second,
            width = 180.dp,
            bold = true
        )
    }
}

@Composable
private fun TableRow(
    first: String,
    second: String,
    boldValue: Boolean = false
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(
                rememberScrollState()
            )
    ) {

        TableCell(
            text = first,
            width = 145.dp
        )

        TableCell(
            text = second,
            width = 180.dp,
            bold = boldValue
        )
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false
) {

    Box(
        modifier = Modifier
            .width(width)
            .padding(
                horizontal = 2.dp,
                vertical = 2.dp
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(
                horizontal = 7.dp,
                vertical = 8.dp
            )
    ) {

        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight =
                if (bold) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                }
        )
    }
}

@Composable
private fun SectionTitle(
    text: String
) {

    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun EmptyCard(
    text: String
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Text(
            text = text,
            modifier = Modifier.padding(16.dp)
        )
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

        return "🟢 پیشنهاد خرید قوی"
    }

    if (
        score <= 25 &&
        confidence >= 75 &&
        dump >= 75 &&
        heavyOutflow &&
        result.news <= 55 &&
        !heavyInflow
    ) {

        return "🔴 پیشنهاد فروش قوی"
    }

    if (
        score >= 70 &&
        confidence >= 60 &&
        pump >= 65 &&
        !heavyOutflow
    ) {

        return "🟢 پیشنهاد خرید"
    }

    if (
        score <= 35 &&
        confidence >= 60 &&
        dump >= 65 &&
        !heavyInflow
    ) {

        return "🔴 پیشنهاد فروش"
    }

    return "🟡 نگهداری / انتظار"
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
