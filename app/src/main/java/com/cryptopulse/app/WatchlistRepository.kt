package com.cryptopulse.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchlistItem(
val symbol: String,
val buyPrice: Double,
val currentPrice: Double? = null,
val lastUpdated: Long = 0L,
val alertTriggered: Boolean = false
)

class WatchlistRepository(
context: Context
) {

private val preferences =
    context.getSharedPreferences(
        "crypto110_watchlist",
        Context.MODE_PRIVATE
    )

fun getItems(): List<WatchlistItem> {
    val raw = preferences.getString(KEY_ITEMS, null)
        ?: return emptyList()

    return runCatching {
        val array = JSONArray(raw)
        val result = ArrayList<WatchlistItem>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)

            val symbol = item.optString("symbol", "")
                .trim()
                .uppercase()

            val buyPrice = item.optDouble(
                "buyPrice",
                0.0
            )

            if (symbol.isBlank() || buyPrice <= 0.0) {
                continue
            }

            result += WatchlistItem(
                symbol = symbol,
                buyPrice = buyPrice,
                currentPrice =
                    if (item.has("currentPrice")) {
                        item.optDouble(
                            "currentPrice",
                            0.0
                        ).takeIf { it > 0.0 }
                    } else {
                        null
                    },
                lastUpdated = item.optLong(
                    "lastUpdated",
                    0L
                ),
                alertTriggered = item.optBoolean(
                    "alertTriggered",
                    false
                )
            )
        }

        result
    }.getOrDefault(emptyList())
}

fun addItem(
    symbol: String,
    buyPrice: Double
): Boolean {

    val normalized = normalizeSymbol(symbol)

    if (normalized.isBlank() || buyPrice <= 0.0) {
        return false
    }

    val current = getItems().toMutableList()

    if (current.size >= MAX_ITEMS) {
        return false
    }

    if (current.any { it.symbol == normalized }) {
        return false
    }

    current += WatchlistItem(
        symbol = normalized,
        buyPrice = buyPrice
    )

    saveItems(current)

    return true
}

fun updateItem(
    symbol: String,
    buyPrice: Double,
    currentPrice: Double?,
    lastUpdated: Long,
    alertTriggered: Boolean
) {

    val normalized = normalizeSymbol(symbol)

    val updated = getItems()
        .map { item ->
            if (item.symbol == normalized) {
                item.copy(
                    buyPrice = buyPrice,
                    currentPrice = currentPrice,
                    lastUpdated = lastUpdated,
                    alertTriggered = alertTriggered
                )
            } else {
                item
            }
        }

    saveItems(updated)
}

fun updateCurrentPrice(
    symbol: String,
    currentPrice: Double,
    lastUpdated: Long = System.currentTimeMillis()
) {

    val normalized = normalizeSymbol(symbol)

    val updated = getItems()
        .map { item ->
            if (item.symbol == normalized) {
                item.copy(
                    currentPrice = currentPrice,
                    lastUpdated = lastUpdated
                )
            } else {
                item
            }
        }

    saveItems(updated)
}

fun setAlertTriggered(
    symbol: String,
    triggered: Boolean
) {

    val normalized = normalizeSymbol(symbol)

    val updated = getItems()
        .map { item ->
            if (item.symbol == normalized) {
                item.copy(
                    alertTriggered = triggered
                )
            } else {
                item
            }
        }

    saveItems(updated)
}

fun removeItem(symbol: String) {

    val normalized = normalizeSymbol(symbol)

    val updated = getItems()
        .filterNot {
            it.symbol == normalized
        }

    saveItems(updated)
}

fun clearAll() {
    preferences.edit()
        .remove(KEY_ITEMS)
        .apply()
}

fun isFull(): Boolean {
    return getItems().size >= MAX_ITEMS
}

private fun saveItems(
    items: List<WatchlistItem>
) {

    val array = JSONArray()

    items.take(MAX_ITEMS).forEach { item ->

        val json = JSONObject()

        json.put(
            "symbol",
            item.symbol
        )

        json.put(
            "buyPrice",
            item.buyPrice
        )

        item.currentPrice?.let {
            json.put(
                "currentPrice",
                it
            )
        }

        json.put(
            "lastUpdated",
            item.lastUpdated
        )

        json.put(
            "alertTriggered",
            item.alertTriggered
        )

        array.put(json)
    }

    preferences.edit()
        .putString(
            KEY_ITEMS,
            array.toString()
        )
        .apply()
}

private fun normalizeSymbol(
    value: String
): String {

    val symbol = value
        .trim()
        .uppercase()
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

companion object {

    const val MAX_ITEMS = 5

    private const val KEY_ITEMS =
        "watchlist_items"
}

}
