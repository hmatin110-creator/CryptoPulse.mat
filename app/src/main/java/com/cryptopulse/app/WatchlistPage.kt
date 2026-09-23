package com.cryptopulse.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@androidx.compose.runtime.Composable
fun WatchlistPage(
onBackHome: () -> Unit
) {

val context =
    androidx.compose.ui.platform.LocalContext.current

val scope =
    androidx.compose.runtime.rememberCoroutineScope()

val watchlistRepository =
    remember {
        WatchlistRepository(context)
    }

val analysisRepository =
    remember {
        WatchlistAnalysisRepository(context)
    }

val priceRepository =
    remember {
        WatchlistPriceRepository()
    }

var items by remember {
    mutableStateOf(
        watchlistRepository.getItems()
    )
}

var analyses by remember {
    mutableStateOf(
        analysisRepository.getAll()
    )
}

var symbolInput by remember {
    mutableStateOf("")
}

var buyPriceInput by remember {
    mutableStateOf("")
}

var message by remember {
    mutableStateOf("")
}

var loading by remember {
    mutableStateOf(false)
}

var analysisRunning by remember {
    mutableStateOf(false)
}

fun reloadAnalysis() {
    analyses =
        analysisRepository.getAll()
}

fun refreshPrices() {

    if (loading) {
        return
    }

    loading = true
    message = ""

    scope.launch {

        try {

            val currentItems =
                watchlistRepository.getItems()

            if (currentItems.isEmpty()) {
                items = emptyList()
                analyses =
                    analysisRepository.getAll()
                return@launch
            }

            val prices =
                priceRepository.getPrices(
                    currentItems.map {
                        it.symbol
                    }
                )

            val now =
                System.currentTimeMillis()

            currentItems.forEach { item ->

                val price =
                    prices[item.symbol]

                if (
                    price != null &&
                    price > 0.0
                ) {

                    watchlistRepository.updateItem(
                        symbol = item.symbol,
                        buyPrice = item.buyPrice,
                        currentPrice = price,
                        lastUpdated = now,
                        alertTriggered = item.alertTriggered
                    )
                }
            }

            items =
                watchlistRepository.getItems()

            reloadAnalysis()

            message =
                if (prices.isEmpty()) {
                    "قیمت‌ها دریافت نشدند"
                } else {
                    "قیمت‌ها به‌روزرسانی شدند"
                }

        } catch (_: Exception) {

            message =
                "خطا در دریافت قیمت‌ها"

        } finally {

            loading = false
        }
    }
}

fun runAnalysisNow() {

    if (
        analysisRunning ||
        items.isEmpty()
    ) {
        return
    }

    analysisRunning = true
    message =
        "تحلیل واچ‌لیست شروع شد..."

    val analysisStart =
        System.currentTimeMillis()

    WatchlistWorkScheduler.runNow(
        context
    )

    scope.launch {

        var completed = false

        repeat(20) {

            delay(2500)

            items =
                watchlistRepository.getItems()

            reloadAnalysis()

            val currentItems =
                watchlistRepository.getItems()

            val currentAnalyses =
                analysisRepository.getAll()

            completed =
                currentItems.isNotEmpty() &&
                    currentItems.all { item ->

                        currentAnalyses
                            .firstOrNull {
                                it.symbol ==
                                    item.symbol
                            }
                            ?.lastAnalyzed
                            ?.let {
                                it >= analysisStart
                            } == true
                    }

            if (completed) {
                return@repeat
            }
        }

        items =
            watchlistRepository.getItems()

        reloadAnalysis()

        analysisRunning = false

        message =
            if (completed) {
                "✅ تحلیل واچ‌لیست با موفقیت به‌روزرسانی شد"
            } else {
                "⏳ تحلیل در پس‌زمینه ادامه دارد؛ چند لحظه بعد دوباره صفحه را بررسی کن"
            }
    }
}

fun addCoin() {

    val symbol =
        symbolInput.trim()

    val buyPrice =
        buyPriceInput
            .trim()
            .replace(",", ".")
            .toDoubleOrNull()

    if (symbol.isBlank()) {

        message =
            "نماد ارز را وارد کن"

        return
    }

    if (
        buyPrice == null ||
        buyPrice <= 0
    ) {

        message =
            "قیمت خرید معتبر نیست"

        return
    }

    val added =
        watchlistRepository.addItem(
            symbol = symbol,
            buyPrice = buyPrice
        )

    if (added) {

        symbolInput = ""
        buyPriceInput = ""

        items =
            watchlistRepository.getItems()

        message =
            "✅ ارز به واچ‌لیست اضافه شد"

    } else {

        message =
            if (
                watchlistRepository.isFull()
            ) {
                "حداکثر ۵ ارز می‌توانی اضافه کنی"
            } else {
                "این ارز قبلاً در واچ‌لیست وجود دارد"
            }
    }
}

fun removeCoin(symbol: String) {

    watchlistRepository.removeItem(
        symbol
    )

    analysisRepository.remove(
        symbol
    )

    items =
        watchlistRepository.getItems()

    analyses =
        analysisRepository.getAll()

    message =
        "ارز از واچ‌لیست حذف شد"
}

LaunchedEffect(Unit) {

    items =
        watchlistRepository.getItems()

    reloadAnalysis()

    refreshPrices()
}

LazyColumn(
    modifier =
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    verticalArrangement =
        Arrangement.spacedBy(12.dp)
) {

    item {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            OutlinedButton(
                onClick = onBackHome
            ) {
                Text("← خانه")
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text = "⭐ واچ‌لیست",
                style =
                    MaterialTheme.typography.headlineSmall,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }

    item {

        Card(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier =
                    Modifier.padding(14.dp)
            ) {

                Text(
                    text = "➕ افزودن ارز",
                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                OutlinedTextField(
                    value = symbolInput,
                    onValueChange = {
                        symbolInput = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("نماد ارز")
                    },
                    placeholder = {
                        Text("BTC یا BTCUSDT")
                    }
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = buyPriceInput,
                    onValueChange = {
                        buyPriceInput = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),
                    label = {
                        Text("قیمت خرید")
                    }
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Button(
                    onClick = {
                        addCoin()
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("افزودن به واچ‌لیست")
                }
            }
        }
    }

    if (message.isNotBlank()) {

        item {

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text = message,
                    modifier =
                        Modifier.padding(12.dp)
                )
            }
        }
    }

    item {

        Card(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier =
                    Modifier.padding(14.dp)
            ) {

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text = "📈 ارزهای من",
                        fontWeight =
                            FontWeight.Bold
                    )

                    OutlinedButton(
                        onClick = {
                            refreshPrices()
                        },
                        enabled =
                            !loading &&
                                !analysisRunning
                    ) {

                        Text(
                            if (loading) {
                                "در حال دریافت..."
                            } else {
                                "🔄 قیمت‌ها"
                            }
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Button(
                    onClick = {
                        runAnalysisNow()
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    enabled =
                        items.isNotEmpty() &&
                            !analysisRunning &&
                            !loading
                ) {

                    Text(
                        if (analysisRunning) {
                            "🤖 در حال تحلیل..."
                        } else {
                            "🤖 تحلیل الآن"
                        }
                    )
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        "تحلیل واچ‌لیست هر ۶ ساعت انجام می‌شود و افت ۵٪ هشدار ایجاد می‌کند.",
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (items.isEmpty()) {

        item {

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        "هنوز ارزی به واچ‌لیست اضافه نشده است.",
                    modifier =
                        Modifier.padding(16.dp)
                )
            }
        }

    } else {

        items(
            items = items,
            key = {
                it.symbol
            }
        ) { item ->

            val analysis =
                analyses.firstOrNull {
                    it.symbol ==
                        item.symbol
                }

            WatchlistItemCard(
                item = item,
                analysis = analysis,
                onRemove = {
                    removeCoin(
                        item.symbol
                    )
                }
            )
        }
    }
}

}

@androidx.compose.runtime.Composable
private fun WatchlistItemCard(
item: WatchlistItem,
analysis: WatchlistAnalysisSnapshot?,
onRemove: () -> Unit
) {

Card(
    modifier =
        Modifier.fillMaxWidth()
) {

    Column(
        modifier =
            Modifier.padding(14.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = item.symbol,
                style =
                    MaterialTheme.typography.titleLarge,
                fontWeight =
                    FontWeight.Bold
            )

            OutlinedButton(
                onClick = onRemove
            ) {
                Text("حذف")
            }
        }

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        WatchlistPriceTable(
            item = item
        )

        if (analysis != null) {

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Divider()

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            WatchlistAnalysisCard(
                item = item,
                analysis = analysis
            )
        }
    }
}

}

@androidx.compose.runtime.Composable
private fun WatchlistPriceTable(
item: WatchlistItem
) {

val current =
    item.currentPrice

val profitPercent =
    if (
        current != null &&
        item.buyPrice > 0
    ) {
        (
            (current - item.buyPrice) /
                item.buyPrice
            ) * 100.0
    } else {
        null
    }

TableRow(
    "قیمت خرید",
    formatPrice(item.buyPrice)
)

TableRow(
    "قیمت فعلی",
    current?.let {
        formatPrice(it)
    } ?: "-"
)

TableRow(
    "سود / زیان",
    profitPercent?.let {
        formatPercent(it)
    } ?: "-"
)

TableRow(
    "وضعیت هشدار",
    if (item.alertTriggered) {
        "🔴 افت بیش از ۵٪"
    } else {
        "🟢 عادی"
    }
)

}

@androidx.compose.runtime.Composable
private fun WatchlistAnalysisCard(
item: WatchlistItem,
analysis: WatchlistAnalysisSnapshot
) {

val signal =
    normalizeAnalysisSignal(
        analysis.signal
    )

val currentPrice =
    item.currentPrice

val profitPercent =
    if (
        currentPrice != null &&
        item.buyPrice > 0
    ) {
        (
            (currentPrice - item.buyPrice) /
                item.buyPrice
            ) * 100.0
    } else {
        null
    }

Text(
    text = "🤖 تحلیل هوشمند",
    fontWeight =
        FontWeight.Bold
)

Spacer(
    modifier =
        Modifier.height(8.dp)
)

Card(
    modifier =
        Modifier.fillMaxWidth()
) {

    Text(
        text = signal,
        modifier =
            Modifier.padding(12.dp),
        style =
            MaterialTheme.typography.titleMedium,
        fontWeight =
            FontWeight.Bold
    )
}

Spacer(
    modifier =
        Modifier.height(10.dp)
)

Card(
    modifier =
        Modifier.fillMaxWidth()
) {

    Column(
        modifier =
            Modifier.padding(12.dp)
    ) {

        Text(
            text = "💰 وضعیت قیمت",
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(6.dp)
        )

        TableRow(
            "قیمت خرید",
            formatPrice(item.buyPrice)
        )

        TableRow(
            "قیمت فعلی",
            currentPrice?.let {
                formatPrice(it)
            } ?: "دریافت نشد"
        )

        TableRow(
            "سود / زیان",
            profitPercent?.let {
                formatPercent(it)
            } ?: "دریافت نشد"
        )
    }
}

Spacer(
    modifier =
        Modifier.height(10.dp)
)

WatchlistAnalysisTable(
    analysis = analysis
)

if (
    analysis.entryLow != null &&
    analysis.entryHigh != null &&
    analysis.stopLoss != null &&
    analysis.tp1 != null &&
    analysis.tp2 != null
) {

    Spacer(
        modifier =
            Modifier.height(12.dp)
    )

    Divider()

    Spacer(
        modifier =
            Modifier.height(12.dp)
    )

    TradePlanCard(
        analysis = analysis
    )
}

Spacer(
    modifier =
        Modifier.height(10.dp)
)

Text(
    text =
        if (analysis.finalAnalysis.isBlank()) {
            "تحلیل نهایی ثبت نشده است"
        } else {
            analysis.finalAnalysis
        },
    style =
        MaterialTheme.typography.bodySmall
)

}

@androidx.compose.runtime.Composable
private fun WatchlistAnalysisTable(
analysis: WatchlistAnalysisSnapshot
) {

TableRow(
    "امتیاز",
    "${analysis.score}/100"
)

TableRow(
    "اعتماد",
    "${analysis.confidence}/100"
)

TableRow(
    "احتمال پامپ",
    "${analysis.pump}/100"
)

TableRow(
    "احتمال دامپ",
    "${analysis.dump}/100"
)

TableRow(
    "جریان پول",
    "${analysis.moneyFlow}/100"
)

TableRow(
    "روند",
    "${analysis.trend}/100"
)

TableRow(
    "اخبار",
    "${analysis.news}/100"
)

TableRow(
    "آخرین تحلیل",
    formatDateTime(
        analysis.lastAnalyzed
    )
)

}

@androidx.compose.runtime.Composable
private fun TradePlanCard(
analysis: WatchlistAnalysisSnapshot
) {

val isSell =
    analysis.signal.contains(
        "SELL",
        ignoreCase = true
    ) ||
        analysis.signal.contains(
            "فروش"
        )

val entryLow =
    analysis.entryLow

val entryHigh =
    analysis.entryHigh

val stopLoss =
    analysis.stopLoss

val tp1 =
    analysis.tp1

val tp2 =
    analysis.tp2

val riskReward =
    analysis.riskReward

Card(
    modifier =
        Modifier.fillMaxWidth()
) {

    Column(
        modifier =
            Modifier.padding(12.dp)
    ) {

        Text(
            text =
                if (isSell) {
                    "📉 برنامه معامله فروش"
                } else {
                    "📈 برنامه معامله خرید"
                },
            fontWeight =
                FontWeight.Bold,
            style =
                MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        TableRow(
            "ورود",
            if (
                entryLow != null &&
                entryHigh != null
            ) {
                "${formatPrice(entryLow)} تا ${formatPrice(entryHigh)}"
            } else {
                "-"
            }
        )

        TableRow(
            "حد ضرر",
            stopLoss?.let {
                formatPrice(it)
            } ?: "-"
        )

        TableRow(
            "هدف ۱",
            tp1?.let {
                formatPrice(it)
            } ?: "-"
        )

        TableRow(
            "هدف ۲",
            tp2?.let {
                formatPrice(it)
            } ?: "-"
        )

        TableRow(
            "ریسک به بازده",
            riskReward?.let {
                String.format(
                    Locale.US,
                    "%.2f",
                    it
                )
            } ?: "-"
        )
    }
}

}

@androidx.compose.runtime.Composable
private fun TableRow(
title: String,
value: String
) {

Row(
    modifier =
        Modifier
            .fillMaxWidth()
            .padding(
                vertical = 4.dp
            ),
    horizontalArrangement =
        Arrangement.SpaceBetween
) {

    Text(
        text = title,
        fontWeight =
            FontWeight.Medium
    )

    Text(
        text = value
    )
}

}

private fun normalizeAnalysisSignal(
signal: String
): String {

val value =
    signal.trim()

return when {

    value.contains(
        "STRONG BUY",
        ignoreCase = true
    ) ||
        value.contains(
            "خرید قوی"
        ) ->
        "🚀 پیشنهاد خرید قوی"

    value.contains(
        "BUY",
        ignoreCase = true
    ) ||
        value.contains(
            "خرید"
        ) ->
        "🟢 پیشنهاد خرید"

    value.contains(
        "STRONG SELL",
        ignoreCase = true
    ) ||
        value.contains(
            "فروش قوی"
        ) ->
        "🔥 پیشنهاد فروش قوی"

    value.contains(
        "SELL",
        ignoreCase = true
    ) ||
        value.contains(
            "فروش"
        ) ->
        "🔴 پیشنهاد فروش"

    else ->
        "🟡 نگهداری / انتظار"
}

}

private fun formatPrice(
value: Double
): String {

if (!value.isFinite()) {
    return "-"
}

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

    value >= 0.01 ->
        String.format(
            Locale.US,
            "%.6f",
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

private fun formatPercent(
value: Double
): String {

return String.format(
    Locale.US,
    "%.2f%%",
    value
)

}

private fun formatDateTime(
timestamp: Long
): String {

if (timestamp <= 0) {
    return "-"
}

return java.text.SimpleDateFormat(
    "yyyy/MM/dd HH:mm",
    Locale.US
).format(
    java.util.Date(timestamp)
)

}
