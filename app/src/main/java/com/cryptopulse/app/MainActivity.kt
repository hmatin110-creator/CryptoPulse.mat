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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.Dp
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
            .padding(
                horizontal = 12.dp,
                vertical = 10.dp
            ),
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
                NewsAnalysisCard(result)
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
                    EmptyCard(
                        "هیچ ارزی با پیشنهاد خرید پیدا نشد."
                    )
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
                    modifier = Modifier.height(4.dp)
                )

                SectionTitle(
                    "🏆 Top 10 رتبه‌های 1 تا 100"
                )
            }

            if (scan.top10Top100.isEmpty()) {

                item {
                    EmptyCard(
                        "برای این بخش پیشنهاد خریدی وجود ندارد."
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {

                Text(
                    text =
                        "⚠️ نتایج تحلیل آماری هستند و تضمین سود یا پیش‌بینی قطعی قیمت نیستند.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HeaderSection() {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {

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

        SharedTable(
            columns = listOf(
                "شاخص" to 150.dp,
                "مقدار" to 190.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "شاخص" to 150.dp,
                    "مقدار" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "پیشنهاد نهایی" to 150.dp,
                    signal to 190.dp
                ),
                boldValue = true
            )

            TableDataRow(
                listOf(
                    "Score" to 150.dp,
                    "${result.score}/100" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Confidence" to 150.dp,
                    "${result.confidence}%" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Pump Probability" to 150.dp,
                    "${result.pump}%" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Dump Probability" to 150.dp,
                    "${result.dump}%" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Money Flow" to 150.dp,
                    "${result.moneyFlow}/100" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "News Score" to 150.dp,
                    "${result.news}/100" to 190.dp
                ),
                boldValue = true
            )

            TableDataRow(
                listOf(
                    "Trend" to 150.dp,
                    "${result.trend}/100" to 190.dp
                )
            )
        }
    }
}

@Composable
private fun NewsAnalysisCard(
    result: AnalysisResult
) {

    val newsLabel = when {

        result.news >= 75 ->
            "🟢 اخبار بسیار مثبت"

        result.news >= 60 ->
            "🟢 اخبار مثبت"

        result.news >= 45 ->
            "🟡 اخبار خنثی"

        result.news >= 30 ->
            "🟠 اخبار منفی"

        else ->
            "🔴 اخبار بسیار منفی"
    }

    val newsEffect =
        when {
            result.news >= 70 -> "مثبت"
            result.news <= 30 -> "منفی"
            else -> "خنثی"
        }

    TableCard(
        title = "📰 تحلیل اخبار"
    ) {

        SharedTable(
            columns = listOf(
                "شاخص" to 150.dp,
                "مقدار" to 190.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "شاخص" to 150.dp,
                    "مقدار" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "امتیاز اخبار" to 150.dp,
                    "${result.news}/100" to 190.dp
                ),
                boldValue = true
            )

            TableDataRow(
                listOf(
                    "وضعیت اخبار" to 150.dp,
                    newsLabel to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "اطمینان تحلیل" to 150.dp,
                    "${result.confidence}%" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "اثر در تصمیم نهایی" to 150.dp,
                    newsEffect to 190.dp
                )
            )
        }
    }
}

@Composable
private fun AnalysisTable(
    result: AnalysisResult
) {

    val breakoutText =
        if (result.structure.breakout) {
            "بله"
        } else {
            "خیر"
        }

    val breakdownText =
        if (result.structure.breakdown) {
            "بله"
        } else {
            "خیر"
        }

    TableCard(
        title = "📊 جدول تحلیل بازار"
    ) {

        SharedTable(
            columns = listOf(
                "بخش" to 170.dp,
                "نتیجه" to 190.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "بخش" to 170.dp,
                    "نتیجه" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "ساختار بازار" to 170.dp,
                    result.structure.label to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Breakout" to 170.dp,
                    breakoutText to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Breakdown" to 170.dp,
                    breakdownText to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "حمایت" to 170.dp,
                    formatPrice(result.structure.support) to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "مقاومت" to 170.dp,
                    formatPrice(result.structure.resistance) to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "واگرایی" to 170.dp,
                    result.divergence.label to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "رژیم BTC" to 170.dp,
                    result.btcRegime.label to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "امتیاز اخبار" to 170.dp,
                    "${result.news}/100" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "اطمینان تحلیل" to 170.dp,
                    "${result.confidence}%" to 190.dp
                )
            )

            result.timeframeScores.forEach { entry ->

                TableDataRow(
                    listOf(
                        "تایم‌فریم ${entry.key}" to 170.dp,
                        "${entry.value}/100" to 190.dp
                    )
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

        SharedTable(
            columns = listOf(
                "دوره" to 85.dp,
                "ورود" to 105.dp,
                "ورود سنگین" to 105.dp,
                "خروج" to 105.dp,
                "خروج سنگین" to 105.dp,
                "Net" to 105.dp,
                "وضعیت" to 125.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "دوره" to 85.dp,
                    "ورود" to 105.dp,
                    "ورود سنگین" to 105.dp,
                    "خروج" to 105.dp,
                    "خروج سنگین" to 105.dp,
                    "Net" to 105.dp,
                    "وضعیت" to 125.dp
                )
            )

            if (flow.periods.isEmpty()) {

                TableDataRow(
                    listOf(
                        "داده" to 85.dp,
                        "داده‌ای موجود نیست" to 650.dp
                    )
                )

            } else {

                flow.periods.forEach { entry ->

                    val period = entry.value

                    val unusualInflowText =
                        if (period.unusualInflow > 0) {
                            "بله (${period.unusualInflow})"
                        } else {
                            "خیر"
                        }

                    val unusualOutflowText =
                        if (period.unusualOutflow > 0) {
                            "بله (${period.unusualOutflow})"
                        } else {
                            "خیر"
                        }

                    TableDataRow(
                        listOf(
                            period.title to 85.dp,

                            formatMoney(
                                period.inflowUsd
                            ) to 105.dp,

                            unusualInflowText to 105.dp,

                            formatMoney(
                                period.outflowUsd
                            ) to 105.dp,

                            unusualOutflowText to 105.dp,

                            formatMoney(
                                period.netFlowUsd
                            ) to 105.dp,

                            period.status to 125.dp
                        )
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        SharedTable(
            columns = listOf(
                "شاخص" to 170.dp,
                "مقدار" to 190.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "شاخص" to 170.dp,
                    "مقدار" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "وضعیت کلی" to 170.dp,
                    flow.label to 190.dp
                ),
                boldValue = true
            )

            TableDataRow(
                listOf(
                    "Money Flow Score" to 170.dp,
                    "${flow.score}/100" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Confidence" to 170.dp,
                    "${flow.confidence}%" to 190.dp
                )
            )
        }
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

            SharedTable(
                columns = listOf(
                    "وضعیت" to 150.dp,
                    "توضیح" to 260.dp
                )
            ) {

                TableHeaderRow(
                    listOf(
                        "وضعیت" to 150.dp,
                        "توضیح" to 260.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "برنامه معامله" to 150.dp,
                        "در شرایط فعلی ورود فعال تأیید نشده است." to 260.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "پیشنهاد" to 150.dp,
                        finalSignal(result) to 260.dp
                    ),
                    boldValue = true
                )
            }

        } else {

            SharedTable(
                columns = listOf(
                    "مورد" to 170.dp,
                    "مقدار" to 250.dp
                )
            ) {

                TableHeaderRow(
                    listOf(
                        "مورد" to 170.dp,
                        "مقدار" to 250.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "محدوده ورود" to 170.dp,
                        "${formatPrice(plan.entryLow)} - ${formatPrice(plan.entryHigh)}" to 250.dp
                    ),
                    boldValue = true
                )

                TableDataRow(
                    listOf(
                        "Stop Loss" to 170.dp,
                        formatPrice(plan.stopLoss) to 250.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "TP1" to 170.dp,
                        formatPrice(plan.tp1) to 250.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "TP2" to 170.dp,
                        formatPrice(plan.tp2) to 250.dp
                    )
                )

                TableDataRow(
                    listOf(
                        "Risk / Reward" to 170.dp,
                        formatRR(plan.riskReward) to 250.dp
                    )
                )
            }
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
        title = "🧠 دلایل اصلی تحلیل"
    ) {

        SharedTable(
            columns = listOf(
                "#" to 50.dp,
                "دلیل" to 420.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "#" to 50.dp,
                    "دلیل" to 420.dp
                )
            )

            result.reasons
                .take(10)
                .forEachIndexed { index, reason ->

                    TableDataRow(
                        listOf(
                            "${index + 1}" to 50.dp,
                            reason to 420.dp
                        )
                    )
                }
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

        SharedTable(
            columns = listOf(
                "شاخص" to 190.dp,
                "مقدار" to 190.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "شاخص" to 190.dp,
                    "مقدار" to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "کل بازار بررسی‌شده" to 190.dp,
                    scan.universeCount.toString() to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "ارزهای تحلیل‌شده" to 190.dp,
                    scan.analyzedCount.toString() to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Top 10 کل بازار" to 190.dp,
                    scan.top10All.size.toString() to 190.dp
                )
            )

            TableDataRow(
                listOf(
                    "Top 10 رتبه 1 تا 100" to 190.dp,
                    scan.top10Top100.size.toString() to 190.dp
                )
            )
        }
    }
}

@Composable
private fun CandidateTable(
    candidates: List<ScanCandidate>
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        SharedTable(
            columns = listOf(
                "ارز" to 105.dp,
                "پیشنهاد" to 135.dp,
                "امتیاز تحلیل" to 100.dp,
                "ورود پول" to 105.dp,
                "ورود سنگین پول" to 125.dp,
                "ورود غیرطبیعی پول" to 145.dp
            )
        ) {

            TableHeaderRow(
                listOf(
                    "ارز" to 105.dp,
                    "پیشنهاد" to 135.dp,
                    "امتیاز تحلیل" to 100.dp,
                    "ورود پول" to 105.dp,
                    "ورود سنگین پول" to 125.dp,
                    "ورود غیرطبیعی پول" to 145.dp
                )
            )

            candidates.forEach { candidate ->

                val result = candidate.result

                val heavyInflow =
                    isHeavyInflow(
                        result.moneyFlowDetails.label
                    )

                /*
                 * مهم:
                 * در جدول Top 10 کل بازار و Top 10 رتبه 1 تا 100
                 * ورود غیرطبیعی فقط از سه دوره اخیر بررسی می‌شود:
                 *
                 * 1D
                 * 2D
                 * 3D
                 *
                 * دوره‌های 4D / 1W / 1M / 3M / 6M
                 * در این عدد هیچ دخالتی ندارند.
                 */
                val recentPeriods =
                    listOfNotNull(
                        result.moneyFlowDetails.periods["1D"],
                        result.moneyFlowDetails.periods["2D"],
                        result.moneyFlowDetails.periods["3D"]
                    )

                val unusualInflowCount =
                    recentPeriods.count {
                        it.unusualInflow > 0
                    }

                val heavyInflowText =
                    if (heavyInflow) {
                        "بله"
                    } else {
                        "خیر"
                    }

                val unusualInflowText =
                    if (unusualInflowCount > 0) {
                        "بله ($unusualInflowCount دوره)"
                    } else {
                        "خیر"
                    }

                TableDataRow(
                    listOf(
                        candidate.symbol to 105.dp,

                        finalSignal(result) to 135.dp,

                        "${result.score}/100" to 100.dp,

                        "${result.moneyFlow}/100" to 105.dp,

                        heavyInflowText to 125.dp,

                        unusualInflowText to 145.dp
                    )
                )
            }
        }
    }
}

@Composable
private fun SharedTable(
    columns: List<Pair<String, Dp>>,
    content: @Composable () -> Unit
) {

    val scrollState = rememberScrollState()

    val totalWidth =
        columns.fold(0.dp) { total, column ->
            total + column.second
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    ) {

        Column(
            modifier = Modifier.width(totalWidth)
        ) {

            content()
        }
    }
}

@Composable
private fun TableHeaderRow(
    cells: List<Pair<String, Dp>>
) {

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {

        cells.forEach { (text, width) ->

            TableCell(
                text = text,
                width = width,
                bold = true
            )
        }
    }
}

@Composable
private fun TableDataRow(
    cells: List<Pair<String, Dp>>,
    boldValue: Boolean = false
) {

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {

        cells.forEachIndexed { index, (text, width) ->

            TableCell(
                text = text,
                width = width,
                bold = boldValue && index == cells.lastIndex
            )
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
private fun TableCell(
    text: String,
    width: Dp,
    bold: Boolean = false
) {

    Box(
        modifier = Modifier
            .width(width)
            .padding(
                horizontal = 1.dp,
                vertical = 1.dp
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(
                horizontal = 7.dp,
                vertical = 9.dp
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
