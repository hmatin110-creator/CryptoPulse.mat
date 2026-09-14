package com.cryptopulse.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scheduleAutoLearning(this)

        setContent {
            CryptoPulseApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoPulseApp() {

    val presetCoins = listOf(
        "BTCUSDT",
        "ETHUSDT",
        "SOLUSDT",
        "XRPUSDT",
        "DOGEUSDT"
    )

    var selected by remember { mutableStateOf("BTCUSDT") }
    var customSymbol by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var optimization by remember { mutableStateOf<OptimizationReport?>(null) }
    var optimizeLoading by remember { mutableStateOf(false) }

    var backtest by remember { mutableStateOf<BacktestStats?>(null) }
    var backtestLoading by remember { mutableStateOf(false) }

    var scanLoading by remember { mutableStateOf(false) }
    var scanResults by remember {
        mutableStateOf<List<ScanCandidate>>(emptyList())
    }

    var news by remember { mutableStateOf<NewsSnapshot?>(null) }

    val context = LocalContext.current

    var learningStatus by remember {
        mutableStateOf(AutoLearningStore.status(context))
    }

    val scope = rememberCoroutineScope()

    val repo = remember { LiveRepository() }
    val newsRepo = remember { NewsRepository() }
    val scanner = remember { MarketScanner() }

    fun normalizeSymbol(value: String): String {
        return value
            .trim()
            .uppercase(Locale.US)
            .replace(" ", "")
            .let {
                if (it.endsWith("USDT")) it
                else "${it}USDT"
            }
    }

    fun refresh(symbol: String = selected) {

        val normalized = normalizeSymbol(symbol)

        if (normalized.length < 6) {
            error = "نماد ارز معتبر نیست"
            return
        }

        selected = normalized

        scope.launch {

            loading = true
            error = null

            runCatching {

                repo.load(normalized)

            }.onSuccess { live ->

                val ns = runCatching {
                    newsRepo.load(normalized)
                }.getOrElse {

                    NewsSnapshot(
                        items = emptyList(),
                        score = 50,
                        confidence = 0,
                        bullishCount = 0,
                        bearishCount = 0,
                        marketMovingCount = 0
                    )
                }

                news = ns

                result = AnalysisEngine.analyze(

                    live.candles,

                    MarketFlowData(
                        live.openInterest,
                        live.fundingRate,
                        live.openInterestHistory,
                        live.longShortHistory,
                        live.takerVolumeHistory
                    ),

                    ns.score,
                    ns.confidence,
                    live.btcCandles
                )

            }.onFailure {

                error = it.message ?: "خطا در دریافت داده"

            }

            loading = false
        }
    }

    fun runOptimization() {

        scope.launch {

            optimizeLoading = true

            runCatching {

                repo.load(selected)

            }.onSuccess { live ->

                optimization = WeightOptimizer.optimize(
                    live.candles,
                    listOf(3, 7, 14, 30),
                    AutoLearningStore.getWeights(context)
                )

                learningStatus =
                    AutoLearningStore.status(context)
            }

            optimizeLoading = false
        }
    }

    fun runBacktest() {

        scope.launch {

            backtestLoading = true

            runCatching {

                repo.load(selected)

            }.onSuccess { live ->

                backtest = BacktestEngine.run(
                    live.candles,
                    listOf(3, 7, 14, 30),
                    3
                )
            }

            backtestLoading = false
        }
    }

    fun scanMarket() {

        scope.launch {

            scanLoading = true

            runCatching {

                scanner.scan(120, 35, 12)

            }.onSuccess {

                scanResults = it
            }

            scanLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh("BTCUSDT")
    }

    MaterialTheme(

        colorScheme = darkColorScheme(
            primary = Color(0xFF00D4FF),
            secondary = Color(0xFF7C4DFF),
            background = Color(0xFF08111F),
            surface = Color(0xFF101B2D)
        )

    ) {

        Scaffold(

            containerColor =
                MaterialTheme.colorScheme.background,

            topBar = {

                TopAppBar(

                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0A1424)
                    ),

                    title = {

                        Column {

                            Text(
                                "CryptoPulse",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "AI Crypto Market Analyzer",
                                style =
                                    MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )
            }

        ) { pad ->

            LazyColumn(

                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 14.dp),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp),

                contentPadding =
                    PaddingValues(vertical = 14.dp)

            ) {

                item {

                    Card(
                        shape = RoundedCornerShape(20.dp)
                    ) {

                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            Text(
                                "تحلیل ارز",
                                style =
                                    MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "هر جفت‌ارز USDT را وارد کن و تمام تحلیل‌ها را اجرا کن.",
                                style =
                                    MaterialTheme.typography.bodySmall
                            )

                            OutlinedTextField(

                                value = customSymbol,

                                onValueChange = {
                                    customSymbol =
                                        it.uppercase(Locale.US)
                                },

                                modifier =
                                    Modifier.fillMaxWidth(),

                                singleLine = true,

                                label = {
                                    Text("Symbol")
                                },

                                placeholder = {
                                    Text("مثلاً PEPEUSDT")
                                }
                            )

                            Button(

                                onClick = {

                                    if (customSymbol.isNotBlank()) {
                                        refresh(customSymbol)
                                    }
                                },

                                enabled =
                                    !loading &&
                                    customSymbol.isNotBlank(),

                                modifier =
                                    Modifier.fillMaxWidth(),

                                shape =
                                    RoundedCornerShape(12.dp)

                            ) {

                                Text(
                                    if (loading)
                                        "در حال تحلیل..."
                                    else
                                        "تحلیل این ارز"
                                )
                            }
                        }
                    }
                }

                item {

                    Text(
                        "ارزهای سریع",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {

                        presetCoins.forEach { coin ->

                            FilterChip(

                                selected =
                                    coin == selected,

                                onClick = {
                                    customSymbol = ""
                                    refresh(coin)
                                },

                                label = {
                                    Text(
                                        coin.removeSuffix("USDT")
                                    )
                                }
                            )
                        }
                    }
                }

                item {

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        Button(
                            onClick = {
                                refresh(selected)
                            },
                            enabled = !loading,
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                if (loading)
                                    "Loading..."
                                else
                                    "Refresh"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                scanMarket()
                            },
                            enabled = !scanLoading,
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                if (scanLoading)
                                    "Scanning..."
                                else
                                    "Scanner"
                            )
                        }
                    }
                }

                item {

                    Text(
                        "1D • 2D • 3D • 4D • 1W • 1M • 3M • 6M",
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }

                error?.let { msg ->

                    item {

                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.errorContainer
                                )
                        ) {

                            Text(
                                "Error: $msg",
                                modifier =
                                    Modifier.padding(12.dp),
                                color =
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                result?.let { r ->

                    item {

                        ScoreCard(
                            symbol = selected,
                            result = r
                        )
                    }

                    item {

                        SectionTitle("شاخص‌های اصلی")

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {

                            MetricCard(
                                "Money Flow",
                                r.moneyFlow,
                                Modifier.weight(1f)
                            )

                            MetricCard(
                                "Trend",
                                r.trend,
                                Modifier.weight(1f)
                            )

                            MetricCard(
                                "News",
                                r.news,
                                Modifier.weight(1f)
                            )
                        }
                    }

                    item {

                        SectionTitle("وضعیت بازار")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {

                                InfoRow(
                                    "Money Flow",
                                    r.moneyFlowDetails.label
                                )

                                InfoRow(
                                    "Flow Confidence",
                                    "${r.moneyFlowDetails.confidence}%"
                                )

                                InfoRow(
                                    "Structure",
                                    r.structure.label
                                )

                                InfoRow(
                                    "Divergence",
                                    r.divergence.label
                                )

                                InfoRow(
                                    "BTC Regime",
                                    r.btcRegime.label
                                )
                            }
                        }
                    }

                    item {

                        SectionTitle("امتیاز تایم‌فریم‌ها")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(12.dp)
                            ) {

                                r.timeframeScores
                                    .forEach { (key, value) ->

                                        TimeframeRow(
                                            key,
                                            value
                                        )
                                    }
                            }
                        }
                    }

                    item {

    SectionTitle("جدول ورود و خروج پول")

    Text(
        "برآورد جریان سرمایه در بازه‌های مختلف",
        style = MaterialTheme.typography.bodySmall
    )
}

item {

    val periods = listOf(
        "1D",
        "2D",
        "3D",
        "4D",
        "1W",
        "1M",
        "3M",
        "6M"
    )

    Card(
        shape = RoundedCornerShape(18.dp)
    ) {

        Column(
            modifier = Modifier.padding(10.dp)
        ) {

            Row(
                modifier = Modifier
                    .horizontalScroll(
                        rememberScrollState()
                    )
            ) {

                Column {

                    // Header
                    Row(
                        modifier = Modifier
                            .width(980.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(
                                vertical = 10.dp,
                                horizontal = 6.dp
                            ),

                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {

                        MoneyFlowCell(
                            "بازه",
                            70.dp,
                            true
                        )

                        MoneyFlowCell(
                            "ورود",
                            125.dp,
                            true
                        )

                        MoneyFlowCell(
                            "خروج",
                            125.dp,
                            true
                        )

                        MoneyFlowCell(
                            "خالص",
                            125.dp,
                            true
                        )

                        MoneyFlowCell(
                            "تغییر",
                            100.dp,
                            true
                        )

                        MoneyFlowCell(
                            "ورود غیرعادی",
                            120.dp,
                            true
                        )

                        MoneyFlowCell(
                            "خروج غیرعادی",
                            120.dp,
                            true
                        )

                        MoneyFlowCell(
                            "اطمینان",
                            90.dp,
                            true
                        )

                        MoneyFlowCell(
                            "وضعیت",
                            150.dp,
                            true
                        )
                    }

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    periods.forEach { key ->

                        val p =
                            r.moneyFlowDetails.periods[key]

                        if (p != null) {

                            val statusColor =
                                when {
                                    p.unusualInflow >= 70 ->
                                        Color(0xFF00C853)

                                    p.unusualOutflow >= 70 ->
                                        Color(0xFFFF5252)

                                    p.netFlowUsd > 0 ->
                                        Color(0xFF69F0AE)

                                    p.netFlowUsd < 0 ->
                                        Color(0xFFFF8A80)

                                    else ->
                                        MaterialTheme.colorScheme.onSurface
                                }

                            Row(
                                modifier = Modifier
                                    .width(980.dp)
                                    .padding(
                                        vertical = 8.dp,
                                        horizontal = 6.dp
                                    ),

                                horizontalArrangement =
                                    Arrangement.spacedBy(4.dp),

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                MoneyFlowCell(
                                    p.title.ifBlank {
                                        p.key
                                    },
                                    70.dp,
                                    true
                                )

                                MoneyFlowCell(
                                    formatFlowMoney(
                                        p.inflowUsd
                                    ),
                                    125.dp
                                )

                                MoneyFlowCell(
                                    formatFlowMoney(
                                        p.outflowUsd
                                    ),
                                    125.dp
                                )

                                MoneyFlowCell(
                                    formatFlowMoney(
                                        p.netFlowUsd
                                    ),
                                    125.dp,
                                    valueColor =
                                        statusColor
                                )

                                MoneyFlowCell(
                                    formatPercent(
                                        p.netChangePct
                                    ),
                                    100.dp,
                                    valueColor =
                                        statusColor
                                )

                                MoneyFlowCell(
                                    "${p.unusualInflow}%",
                                    120.dp,
                                    valueColor =
                                        if (
                                            p.unusualInflow >= 70
                                        )
                                            Color(0xFF00C853)
                                        else
                                            MaterialTheme
                                                .colorScheme
                                                .onSurface
                                )

                                MoneyFlowCell(
                                    "${p.unusualOutflow}%",
                                    120.dp,
                                    valueColor =
                                        if (
                                            p.unusualOutflow >= 70
                                        )
                                            Color(0xFFFF5252)
                                        else
                                            MaterialTheme
                                                .colorScheme
                                                .onSurface
                                )

                                MoneyFlowCell(
                                    "${p.confidence}%",
                                    90.dp
                                )

                                MoneyFlowCell(
                                    p.status,
                                    150.dp,
                                    valueColor =
                                        statusColor,
                                    true
                                )
                            }

                            HorizontalDivider(
                                modifier =
                                    Modifier.width(980.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

item {

    Card(
        shape = RoundedCornerShape(18.dp)
    ) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(7.dp)
        ) {

            Text(
                "جمع‌بندی جریان پول",
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            InfoRow(
                "وضعیت",
                r.moneyFlowDetails.label
            )

            InfoRow(
                "امتیاز",
                "${r.moneyFlowDetails.score}/100"
            )

            InfoRow(
                "اطمینان",
                "${r.moneyFlowDetails.confidence}%"
            )

            HorizontalDivider()

            r.moneyFlowDetails.reasons
                .forEach { reason ->

                    Text(
                        "• $reason",
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
        }
    }
}

                    r.tradePlan?.let { p ->

                        item {

                            SectionTitle("Trade Plan")

                            Card {

                                Column(
                                    modifier =
                                        Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(7.dp)
                                ) {

                                    InfoRow(
                                        "Entry",
                                        "${p.entryLow.formatPrice()} - ${p.entryHigh.formatPrice()}"
                                    )

                                    InfoRow(
                                        "Stop Loss",
                                        p.stopLoss.formatPrice()
                                    )

                                    InfoRow(
                                        "TP1",
                                        p.tp1.formatPrice()
                                    )

                                    InfoRow(
                                        "TP2",
                                        p.tp2.formatPrice()
                                    )

                                    InfoRow(
                                        "Risk / Reward",
                                        "%.2f".format(
                                            p.riskReward
                                        )
                                    )
                                }
                            }
                        }
                    }

                    item {

                        SectionTitle("دلایل تحلیل")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                r.reasons.forEach { reason ->

                                    Text(
                                        "• $reason",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (scanResults.isNotEmpty()) {

                    item {

                        SectionTitle(
                            "نتایج Scanner"
                        )

                        Text(
                            "بهترین کاندیداهای فعلی بازار",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }

                    items(
                        scanResults.take(10)
                    ) { x ->

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                Text(
                                    x.symbol.removeSuffix("USDT"),
                                    style =
                                        MaterialTheme.typography.titleMedium,
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    "Pump ${x.result.pump}%  •  " +
                                            "Dump ${x.result.dump}%"
                                )

                                Text(
                                    "Score ${x.result.score}/100  •  " +
                                            "Confidence ${x.result.confidence}%"
                                )

                                Text(
                                    "${x.result.signal}  •  " +
                                            x.result.structure.label +
                                            "  •  " +
                                            x.flowLabel
                                )
                            }
                        }
                    }
                }

                item {

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        OutlinedButton(
                            onClick = {
                                runBacktest()
                            },
                            enabled = !backtestLoading,
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                if (backtestLoading)
                                    "Backtest..."
                                else
                                    "Backtest"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                runOptimization()
                            },
                            enabled = !optimizeLoading,
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                if (optimizeLoading)
                                    "Optimizing..."
                                else
                                    "Optimize"
                            )
                        }
                    }
                }

                backtest?.let { b ->

                    item {

                        SectionTitle("Backtest")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                InfoRow(
                                    "Samples",
                                    b.samples.toString()
                                )

                                InfoRow(
                                    "Signals",
                                    b.evaluatedSignals.toString()
                                )

                                InfoRow(
                                    "Hit Rate",
                                    "%.1f%%".format(
                                        b.hitRate * 100
                                    )
                                )

                                InfoRow(
                                    "Avg Return",
                                    "%.2f%%".format(
                                        b.avgReturn * 100
                                    )
                                )

                                InfoRow(
                                    "Max Drawdown",
                                    "%.2f%%".format(
                                        b.maxDrawdown * 100
                                    )
                                )

                                InfoRow(
                                    "Brier Score",
                                    "%.4f".format(
                                        b.brierScore
                                    )
                                )
                            }
                        }
                    }
                }

                optimization?.let { o ->

                    item {

                        SectionTitle("Auto Learning")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                InfoRow(
                                    "Baseline",
                                    "%.4f".format(
                                        o.baselineScore
                                    )
                                )

                                InfoRow(
                                    "Optimized",
                                    "%.4f".format(
                                        o.optimizedScore
                                    )
                                )

                                InfoRow(
                                    "Holdout Hit Rate",
                                    "%.1f%%".format(
                                        o.holdoutHitRate * 100
                                    )
                                )

                                InfoRow(
                                    "Status",
                                    if (o.accepted)
                                        "ACCEPTED"
                                    else
                                        "REJECTED"
                                )

                                Text(
                                    o.note,
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                news?.let { ns ->

                    item {

                        SectionTitle("اخبار")

                        Card {

                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(6.dp)
                            ) {

                                Text(
                                    "News Score: ${ns.score}/100",
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Text(
                                    "Confidence: ${ns.confidence}%"
                                )

                                Text(
                                    "Bullish: ${ns.bullishCount}  •  " +
                                            "Bearish: ${ns.bearishCount}  •  " +
                                            "Market-moving: ${ns.marketMovingCount}"
                                )
                            }
                        }
                    }

                    items(
                        ns.items.take(10)
                    ) { n ->

                        Card(
                            onClick = {

                                if (n.link.isNotBlank()) {

                                    runCatching {

                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(n.link)
                                            )
                                        )
                                    }
                                }
                            }
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                Text(
                                    n.source,
                                    style =
                                        MaterialTheme.typography.labelMedium
                                )

                                Text(
                                    n.title,
                                    style =
                                        MaterialTheme.typography.titleMedium
                                )

                                Text(
                                    "${n.category} • " +
                                            "Sentiment ${n.sentiment} • " +
                                            "Impact ${n.impact}/5",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    "Credibility ${n.credibility}% • " +
                                            "Relevance ${n.relevance}%",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {

                    Card {

                        Column(
                            modifier =
                                Modifier.padding(14.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(5.dp)
                        ) {

                            Text(
                                "CryptoPulse",
                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                "Pump/Dump احتمال آماری است و تضمین سود نیست.",
                                style =
                                    MaterialTheme.typography.bodySmall
                            )

                            Text(
                                "Auto Learning: $learningStatus",
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(
    symbol: String,
    result: AnalysisResult
) {

    val signalColor = when (result.signal) {
        "BUY" -> Color(0xFF00C853)
        "SELL" -> Color(0xFFFF5252)
        else -> Color(0xFFFFB300)
    }

    Card(
        shape = RoundedCornerShape(22.dp)
    ) {

        Column(
            modifier =
                Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column {

                    Text(
                        symbol,
                        style =
                            MaterialTheme.typography.headlineSmall,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(
                        "Full Market Analysis",
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }

                Surface(
                    color = signalColor,
                    shape =
                        RoundedCornerShape(12.dp)
                ) {

                    Text(
                        result.signal,
                        modifier =
                            Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 8.dp
                            ),
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            HorizontalDivider()

            Text(
                "Score ${result.score}/100",
                style =
                    MaterialTheme.typography.headlineMedium,
                fontWeight =
                    FontWeight.Bold
            )

            LinearProgressIndicator(
                progress = {
                    result.score / 100f
                },
                modifier =
                    Modifier.fillMaxWidth()
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                Text("Pump ${result.pump}%")
                Text("Dump ${result.dump}%")
                Text("Confidence ${result.confidence}%")
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: Int,
    modifier: Modifier
) {

    Card(
        modifier = modifier
    ) {

        Column(
            modifier =
                Modifier.padding(12.dp)
        ) {

            Text(
                title,
                style =
                    MaterialTheme.typography.labelMedium
            )

            Text(
                "$value",
                style =
                    MaterialTheme.typography.headlineSmall,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TimeframeRow(
    key: String,
    value: Int
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),

        horizontalArrangement =
            Arrangement.SpaceBetween,

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            key,
            fontWeight =
                FontWeight.Bold
        )

        Row(
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text("$value/100")

            Spacer(
                modifier =
                    Modifier.width(10.dp)
            )

            LinearProgressIndicator(
                progress = {
                    value / 100f
                },
                modifier =
                    Modifier.width(100.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            title,
            style =
                MaterialTheme.typography.bodySmall
        )

        Text(
            value,
            fontWeight =
                FontWeight.Bold
        )
    }
}

@Composable
private fun SectionTitle(
    title: String
) {

    Text(
        title,
        style =
            MaterialTheme.typography.titleLarge,
        fontWeight =
            FontWeight.Bold
    )
}

private fun Double.formatPrice(): String {

    return when {
        this >= 1000 ->
            "%.2f".format(this)

        this >= 1 ->
            "%.4f".format(this)

        this >= 0.01 ->
            "%.6f".format(this)

        else ->
            "%.8f".format(this)
    }
}
