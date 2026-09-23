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

val isDropAlert =
    profitPercent != null &&
        profitPercent <= -5.0

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
    when {
        isDropAlert ->
            "🔴 هشدار افت ۵٪ یا بیشتر"

        profitPercent != null ->
            "🟢 هشدار عادی"

        else ->
            "⚪ قیمت فعلی نامشخص"
    }
)

}
  
