package com.cryptopulse.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun WatchlistPage(
onBackHome: () -> Unit
) {
val context = androidx.compose.ui.platform.LocalContext.current
val scope = rememberCoroutineScope()

val repository = remember {
    WatchlistRepository(context)
}

val priceRepository = remember {
    WatchlistPriceRepository()
}

var items by remember {
    mutableStateOf(repository.getItems())
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

fun refreshPrices() {
    if (loading || items.isEmpty()) {
        return
    }

    scope.launch {
        loading = true
        message = "در حال بروزرسانی قیمت‌ها..."

        try {
            val prices =
                priceRepository.getPrices(
                    items.map { it.symbol }
                )

            val now = System.currentTimeMillis()

            items.forEach { item ->

                val currentPrice =
                    prices[item.symbol]

                if (currentPrice != null) {

                    repository.updateCurrentPrice(
                        symbol = item.symbol,
                        currentPrice = currentPrice,
                        lastUpdated = now
                    )
                }
            }

            items = repository.getItems()

            message =
                if (prices.isEmpty()) {
                    "قیمت‌ها دریافت نشدند."
                } else {
                    "قیمت‌ها بروزرسانی شدند."
                }

        } catch (e: Exception) {

            message =
                "خطا در بروزرسانی: ${e.message ?: "خطای نامشخص"}"

        } finally {
            loading = false
        }
    }
}

fun addCoin() {

    val normalized =
        normalizeWatchlistSymbol(symbolInput)

    val buyPrice =
        buyPriceInput
            .trim()
            .replace(",", "")
            .replace(" ", "")
            .toDoubleOrNull()

    when {

        normalized.isBlank() -> {
            message = "نماد ارز را وارد کنید."
        }

        buyPrice == null || buyPrice <= 0.0 -> {
            message = "قیمت خرید معتبر وارد کنید."
        }

        items.size >= WatchlistRepository.MAX_ITEMS -> {
            message = "حداکثر ۵ ارز می‌توانید در واچ‌لیست قرار دهید."
        }

        items.any { it.symbol == normalized } -> {
            message = "$normalized قبلاً در واچ‌لیست قرار دارد."
        }

        else -> {

            val added =
                repository.addItem(
                    symbol = normalized,
                    buyPrice = buyPrice
                )

            if (added) {

                items = repository.getItems()

                symbolInput = ""
                buyPriceInput = ""

                message =
                    "$normalized با قیمت خرید ${formatWatchPrice(buyPrice)} ذخیره شد."

                refreshPrices()

            } else {

                message =
                    "امکان اضافه‌کردن این ارز وجود ندارد."
            }
        }
    }
}

fun removeCoin(symbol: String) {

    repository.removeItem(symbol)

    items = repository.getItems()

    message =
        "$symbol از واچ‌لیست حذف شد."
}

LaunchedEffect(Unit) {

    items = repository.getItems()

    if (items.isNotEmpty()) {
        refreshPrices()
    }
}

Column(
    modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = "⭐ واچ‌لیست",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "حداکثر ۵ ارز را با قیمت خرید خودت ذخیره کن.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "قیمت خرید دستی است و با قیمت بازار جایگزین نمی‌شود.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Text(
                text = "➕ افزودن ارز",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = symbolInput,
                onValueChange = {
                    symbolInput =
                        it.uppercase(Locale.US)
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

            OutlinedTextField(
                value = buyPriceInput,
                onValueChange = {
                    buyPriceInput = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("قیمت خرید")
                },
                placeholder = {
                    Text("مثلاً 100000")
                },
                singleLine = true
            )

            Button(
                onClick = {
                    addCoin()
                },
                enabled = !loading &&
                        items.size < WatchlistRepository.MAX_ITEMS,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ذخیره در واچ‌لیست")
            }

            Text(
                text = "${items.size} از ${WatchlistRepository.MAX_ITEMS} ارز استفاده شده",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (message.isNotBlank()) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {

            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "📈 ارزهای من",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = {
                    refreshPrices()
                },
                enabled = !loading && items.isNotEmpty()
            ) {

                if (loading) {

                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(18.dp)
                            .height(18.dp),
                        strokeWidth = 2.dp
                    )

                    Spacer(
                        modifier = Modifier.width(6.dp)
                    )
                }

                Text(
                    text =
                        if (loading) {
                            "بروزرسانی..."
                        } else {
                            "🔄 بروزرسانی"
                        }
                )
            }
        }
    }

    if (items.isEmpty()) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {

            Text(
                text = "هنوز ارزی به واچ‌لیست اضافه نکرده‌ای.",
                modifier = Modifier.padding(16.dp)
            )
        }

    } else {

        items.forEach { item ->

            WatchlistItemCard(
                item = item,
                onRemove = {
                    removeCoin(item.symbol)
                }
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Text(
                text = "🔔 هشدار کاهش ۵٪",
                fontWeight = FontWeight.Bold
            )

            Text(
                text =
                    "در مرحله بعد، اگر قیمت فعلی به ۵٪ یا بیشتر پایین‌تر از قیمت خرید برسد، نوتیفیکیشن هشدار فعال می‌شود."
            )

            Text(
                text =
                    "همچنین تحلیل خودکار واچ‌لیست هر ۶ ساعت اضافه خواهد شد.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    OutlinedButton(
        onClick = onBackHome,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("← بازگشت به خانه")
    }

    Spacer(
        modifier = Modifier.height(12.dp)
    )
}

}

@Composable
private fun WatchlistItemCard(
item: WatchlistItem,
onRemove: () -> Unit
) {

val currentPrice = item.currentPrice

val changePercent =
    if (currentPrice != null &&
        item.buyPrice > 0.0
    ) {
        ((currentPrice - item.buyPrice) /
                item.buyPrice) * 100.0
    } else {
        null
    }

val status =
    when {
        changePercent == null ->
            "⏳ قیمت فعلی دریافت نشده"

        changePercent <= -5.0 ->
            "🔴 کاهش ۵٪ یا بیشتر"

        changePercent < 0.0 ->
            "🟠 در محدوده کاهش"

        changePercent >= 5.0 ->
            "🟢 رشد ۵٪ یا بیشتر"

        else ->
            "🟡 نزدیک قیمت خرید"
    }

Card(
    modifier = Modifier.fillMaxWidth()
) {

    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = item.symbol,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = onRemove
            ) {
                Text("حذف")
            }
        }

        WatchlistTable(
            item = item,
            changePercent = changePercent,
            status = status
        )
    }
}

}

@Composable
private fun WatchlistTable(
item: WatchlistItem,
changePercent: Double?,
status: String
) {

val columns = listOf(
    "مورد" to 145.dp,
    "مقدار" to 190.dp
)

val scrollState = rememberScrollState()

Box(
    modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(scrollState)
) {

    Column(
        modifier = Modifier.width(335.dp)
    ) {

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "مورد",
                "مقدار"
            ),
            bold = true
        )

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "💰 قیمت خرید",
                formatWatchPrice(item.buyPrice)
            )
        )

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "📈 قیمت فعلی",
                item.currentPrice?.let {
                    formatWatchPrice(it)
                } ?: "دریافت نشده"
            ),
            bold = true
        )

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "📊 سود / زیان",
                changePercent?.let {
                    formatPercent(it)
                } ?: "—"
            ),
            bold = true
        )

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "📌 وضعیت",
                status
            )
        )

        WatchlistTableRow(
            cells = columns,
            values = listOf(
                "🕒 آخرین بروزرسانی",
                formatLastUpdated(item.lastUpdated)
            )
        )
    }
}

}

@Composable
private fun WatchlistTableRow(
cells: List<Pair<String, androidx.compose.ui.unit.Dp>>,
values: List<String>,
bold: Boolean = false
) {

Row(
    modifier = Modifier.fillMaxWidth()
) {

    cells.forEachIndexed { index, (_, width) ->

        Box(
            modifier = Modifier
                .width(width)
                .padding(1.dp)
                .padding(
                    horizontal = 7.dp,
                    vertical = 8.dp
                )
        ) {

            Text(
                text = values.getOrElse(index) {
                    ""
                },
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
}

}

private fun normalizeWatchlistSymbol(
value: String
): String {

val symbol =
    value.trim()
        .uppercase(Locale.US)
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")

if (symbol.isBlank()) {
    return ""
}

return when {

    symbol.endsWith("USDT") ->
        symbol

    symbol.endsWith("USD") ->
        symbol.removeSuffix("USD") + "USDT"

    else ->
        symbol + "USDT"
}

}

private fun formatWatchPrice(
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

private fun formatPercent(
value: Double
): String {

val sign =
    if (value > 0.0) "+" else ""

return sign +
        String.format(
            Locale.US,
            "%.2f%%",
            value
        )

}

private fun formatLastUpdated(
timestamp: Long
): String {

if (timestamp <= 0L) {
    return "هنوز بروزرسانی نشده"
}

val date =
    java.text.SimpleDateFormat(
        "yyyy/MM/dd HH:mm",
        Locale.US
    ).apply {
        timeZone =
            java.util.TimeZone.getDefault()
    }

return date.format(
    java.util.Date(timestamp)
)

}
