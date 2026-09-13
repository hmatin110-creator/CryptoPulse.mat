package com.cryptopulse.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

val coins = listOf(
    "BTCUSDT",
    "ETHUSDT",
    "SOLUSDT",
    "XRPUSDT",
    "DOGEUSDT"
)

var selected by remember { mutableStateOf(coins.first()) }

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

fun refresh() {
    scope.launch {
        loading = true
        error = null

        runCatching {
            repo.load(selected)
        }.onSuccess { live ->

            val ns = runCatching {
                newsRepo.load(selected)
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

            learningStatus = AutoLearningStore.status(context)
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

LaunchedEffect(selected) {
    refresh()
}

MaterialTheme {

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("CryptoPulse • News + Market")
                }
            )
        }
    ) { pad ->

        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                Text(
                    "تحلیل چندتایم‌فریمی",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text("1D • 2D • 3D • 4D • 1W • 1M")
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    coins.forEach { coin ->
                        FilterChip(
                            selected = coin == selected,
                            onClick = {
                                selected = coin
                            },
                            label = {
                                Text(coin.removeSuffix("USDT"))
                            }
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { refresh() },
                    enabled = !loading
                ) {
                    Text(
                        if (loading)
                            "در حال دریافت..."
                        else
                            "بروزرسانی"
                    )
                }
            }

            item {
                Button(
                    onClick = { scanMarket() },
                    enabled = !scanLoading
                ) {
                    Text(
                        if (scanLoading)
                            "در حال اسکن ۱۲۰+ ارز..."
                        else
                            "🔎 اسکن هوشمند ۱۲۰+ ارز"
                    )
                }
            }

            item {
                Button(
                    onClick = { runBacktest() },
                    enabled = !backtestLoading
                ) {
                    Text(
                        if (backtestLoading)
                            "در حال بک‌تست..."
                        else
                            "📊 بک‌تست موتور سیگنال"
                    )
                }
            }

            item {
                Button(
                    onClick = { runOptimization() },
                    enabled = !optimizeLoading
                ) {
                    Text(
                        if (optimizeLoading)
                            "در حال بهینه‌سازی وزن‌ها..."
                        else
                            "🧠 بهینه‌سازی خودکار وزن‌ها"
                    )
                }
            }

            item {
                Text(
                    "یادگیری خودکار هفتگی: $learningStatus",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            optimization?.let { o ->

                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {

                            Text(
                                "بهینه‌سازی Walk-Forward",
                                style = MaterialTheme.typography.titleLarge
                            )

                            Text(
                                "Baseline objective: ${
                                    "%.4f".format(o.baselineScore)
                                } • Optimized: ${
                                    "%.4f".format(o.optimizedScore)
                                }"
                            )

                            Text(
                                "Holdout Hit Rate: ${
                                    "%.1f".format(o.holdoutHitRate * 100)
                                }% • Return: ${
                                    "%.2f".format(o.holdoutReturn * 100)
                                }% • Brier: ${
                                    "%.4f".format(o.holdoutBrier)
                                } • DD: ${
                                    "%.2f".format(o.holdoutDrawdown * 100)
                                }%"
                            )

                            Text(
                                "Weights → " +
                                        "Tech ${
                                            "%.0f".format(
                                                o.optimized.technical * 100
                                            )
                                        }% | " +
                                        "Flow ${
                                            "%.0f".format(
                                                o.optimized.moneyFlow * 100
                                            )
                                        }% | " +
                                        "TF ${
                                            "%.0f".format(
                                                o.optimized.timeframe * 100
                                            )
                                        }% | " +
                                        "Structure ${
                                            "%.0f".format(
                                                o.optimized.structure * 100
                                            )
                                        }% | " +
                                        "Div ${
                                            "%.0f".format(
                                                o.optimized.divergence * 100
                                            )
                                        }% | " +
                                        "News ${
                                            "%.0f".format(
                                                o.optimized.news * 100
                                            )
                                        }% | " +
                                        "BTC ${
                                            "%.0f".format(
                                                o.optimized.btc * 100
                                            )
                                        }%",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text(
                                "Holdout improvement: ${
                                    "%.4f".format(o.holdoutImprovement)
                                } • ${
                                    if (o.accepted)
                                        "ACCEPTED"
                                    else
                                        "REJECTED"
                                }",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text(
                                o.note,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            backtest?.let { b ->

                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {

                            Text(
                                "نتیجه بک‌تست چندافقی",
                                style = MaterialTheme.typography.titleLarge
                            )

                            Text(
                                "Samples: ${b.samples} • Signals: ${b.evaluatedSignals}"
                            )

                            Text(
                                "BUY: ${b.buySignals} • SELL: ${b.sellSignals}"
                            )

                            Text(
                                "Avg Hit Rate: ${
                                    "%.1f".format(b.hitRate * 100)
                                }%"
                            )

                            Text(
                                "Avg Net Return: ${
                                    "%.2f".format(b.avgReturn * 100)
                                }%"
                            )

                            Text(
                                "Worst Max Drawdown: ${
                                    "%.2f".format(b.maxDrawdown * 100)
                                }%"
                            )

                            Text(
                                "Avg Brier Score: ${
                                    "%.4f".format(b.brierScore)
                                }"
                            )

                            Text(
                                "هزینه هر طرف: fee ${
                                    "%.2f".format(b.feeRate * 100)
                                }% + slippage ${
                                    "%.2f".format(b.slippageRate * 100)
                                }%",
                                style = MaterialTheme.typography.bodySmall
                            )

                            b.horizons.forEach { h ->

                                Text(
                                    "${h.horizon}D: " +
                                            "signals ${h.signals} | " +
                                            "hit ${
                                                "%.1f".format(
                                                    h.hitRate * 100
                                                )
                                            }% | " +
                                            "net ${
                                                "%.2f".format(
                                                    h.avgReturn * 100
                                                )
                                            }% | " +
                                            "DD ${
                                                "%.2f".format(
                                                    h.maxDrawdown * 100
                                                )
                                            }% | " +
                                            "Brier ${
                                                "%.3f".format(
                                                    h.brierScore
                                                )
                                            }",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(
                                b.note,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (scanResults.isNotEmpty()) {

                item {
                    Text(
                        "نتایج اسکن هوشمند بازار",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        "ابتدا حجم ۱۲۰+ ارز بررسی می‌شود؛ سپس کاندیداهای برتر با Money Flow و News غنی می‌شوند.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                scanResults
                    .take(10)
                    .forEachIndexed { idx, x ->

                        item {

                            Card {

                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {

                                    Text(
                                        "${idx + 1}. ${
                                            x.symbol.removeSuffix("USDT")
                                        }",
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Text(
                                        "Pump ${x.result.pump}% • " +
                                                "Dump ${x.result.dump}% • " +
                                                "Score ${x.result.score} • " +
                                                "Confidence ${x.result.confidence}%"
                                    )

                                    Text(
                                        "${x.result.signal} • " +
                                                "${x.result.structure.label} • " +
                                                "${x.flowLabel} • " +
                                                "News ${x.newsScore}"
                                    )
                                }
                            }
                        }
                    }
            }

            error?.let { msg ->

                item {
                    Text(
                        "خطا: $msg",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            result?.let { r ->

                item {

                    Card {

                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {

                            Text(
                                selected,
                                style = MaterialTheme.typography.titleLarge
                            )

                            Text(
                                "${r.signal} • Score ${r.score}/100",
                                style = MaterialTheme.typography.headlineMedium
                            )

                            Text(
                                "Pump: ${r.pump}%   Dump: ${r.dump}%"
                            )

                            Text(
                                "Confidence: ${r.confidence}%"
                            )

                            Text(
                                "Money Flow: ${r.moneyFlow}  | " +
                                        "Trend: ${r.trend}  | " +
                                        "News: ${r.news}"
                            )

                            Text(
                                "Flow Status: " +
                                        "${r.moneyFlowDetails.label} • " +
                                        "Confidence ${r.moneyFlowDetails.confidence}%"
                            )

                            Text(
                                "Market Structure: ${r.structure.label} | " +
                                        "S ${"%.4f".format(r.structure.support)} | " +
                                        "R ${"%.4f".format(r.structure.resistance)}"
                            )

                            Text(
                                "Divergence: ${r.divergence.label} • " +
                                        "BTC Regime: ${r.btcRegime.label}"
                            )
                        }
                    }
                }

                item {
                    Text(
                        "امتیاز تایم‌فریم‌ها",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                r.timeframeScores.forEach { (key, value) ->

                    item {
                        Text("$key: $value/100")
                    }
                }

                item {
                    Text(
                        "Money Flow",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    Text(
                        "${r.moneyFlowDetails.label} • " +
                                "${r.moneyFlowDetails.score}/100"
                    )
                }

                r.moneyFlowDetails.reasons.forEach { reason ->

                    item {
                        Text("• $reason")
                    }
                }

                r.tradePlan?.let { p ->

                    item {

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {

                            Text(
                                "Trade Plan",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                "Entry: ${
                                    "%.4f".format(p.entryLow)
                                } - ${
                                    "%.4f".format(p.entryHigh)
                                }"
                            )

                            Text(
                                "SL: ${
                                    "%.4f".format(p.stopLoss)
                                } • TP1: ${
                                    "%.4f".format(p.tp1)
                                } • TP2: ${
                                    "%.4f".format(p.tp2)
                                }"
                            )
                        }
                    }
                }

                item {
                    Text(
                        "دلایل کلی",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                r.reasons.forEach { reason ->

                    item {
                        Text("• $reason")
                    }
                }
            }

            news?.let { ns ->

                item {

                    Text(
                        "اخبار معتبر",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        "News Score ${ns.score}/100 • " +
                                "Confidence ${ns.confidence}%"
                    )

                    Text(
                        "Bullish ${ns.bullishCount} • " +
                                "Bearish ${ns.bearishCount} • " +
                                "Market-moving ${ns.marketMovingCount}"
                    )
                }

                ns.items
                    .take(10)
                    .forEach { n ->

                        item {

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
                                    modifier = Modifier.padding(12.dp)
                                ) {

                                    Text(
                                        n.source,
                                        style = MaterialTheme.typography.labelMedium
                                    )

                                    Text(
                                        n.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Text(
                                        "${n.category} • " +
                                                "Sentiment ${n.sentiment} • " +
                                                "Impact ${n.impact}/5 • " +
                                                "Freshness ${n.recency}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        "Source confidence ${n.credibility}% • " +
                                                "Relevance ${n.relevance}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
            }

            item {

                Text(
                    "منابع خبر: CoinDesk و CryptoSlate. " +
                            "اپ فقط تیتر و لینک را نگه می‌دارد و برای متن کامل به منبع اصلی می‌رود.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {

                Text(
                    "Pump/Dump احتمال آماری است، نه تضمین سود.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

}
