package com.cryptopulse.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchlistAnalysisSnapshot(
val symbol: String,
val signal: String,
val score: Int,
val confidence: Int,
val pump: Int,
val dump: Int,
val moneyFlow: Int,
val news: Int,
val trend: Int,
val finalAnalysis: String,
val lastAnalyzed: Long
)

class WatchlistAnalysisRepository(
context: Context
) {

private val preferences =
    context.getSharedPreferences(
        "crypto110_watchlist_analysis",
        Context.MODE_PRIVATE
    )

fun getAll(): List<WatchlistAnalysisSnapshot> {

    val raw =
        preferences.getString(
            KEY_ANALYSIS,
            null
        ) ?: return emptyList()

    return runCatching {

        val array =
            JSONArray(raw)

        val result =
            ArrayList<WatchlistAnalysisSnapshot>()

        for (i in 0 until array.length()) {

            val item =
                array.getJSONObject(i)

            val symbol =
                item.optString(
                    "symbol",
                    ""
                )
                    .trim()
                    .uppercase()

            if (symbol.isBlank()) {
                continue
            }

            result +=
                WatchlistAnalysisSnapshot(
                    symbol = symbol,

                    signal =
                        item.optString(
                            "signal",
                            "🟡 نگهداری / انتظار"
                        ),

                    score =
                        item.optInt(
                            "score",
                            0
                        ),

                    confidence =
                        item.optInt(
                            "confidence",
                            0
                        ),

                    pump =
                        item.optInt(
                            "pump",
                            0
                        ),

                    dump =
                        item.optInt(
                            "dump",
                            0
                        ),

                    moneyFlow =
                        item.optInt(
                            "moneyFlow",
                            0
                        ),

                    news =
                        item.optInt(
                            "news",
                            0
                        ),

                    trend =
                        item.optInt(
                            "trend",
                            0
                        ),

                    finalAnalysis =
                        item.optString(
                            "finalAnalysis",
                            ""
                        ),

                    lastAnalyzed =
                        item.optLong(
                            "lastAnalyzed",
                            0L
                        )
                )
        }

        result

    }.getOrDefault(
        emptyList()
    )
}

fun get(
    symbol: String
): WatchlistAnalysisSnapshot? {

    val normalized =
        normalizeSymbol(symbol)

    return getAll()
        .firstOrNull {
            it.symbol == normalized
        }
}

fun save(
    snapshot: WatchlistAnalysisSnapshot
) {

    val normalized =
        normalizeSymbol(
            snapshot.symbol
        )

    if (normalized.isBlank()) {
        return
    }

    val current =
        getAll()
            .filterNot {
                it.symbol == normalized
            }
            .toMutableList()

    current +=
        snapshot.copy(
            symbol = normalized
        )

    saveAll(current)
}

fun saveFromResult(
    symbol: String,
    result: AnalysisResult,
    signal: String,
    analyzedAt: Long =
        System.currentTimeMillis()
) {

    save(
        WatchlistAnalysisSnapshot(
            symbol =
                normalizeSymbol(
                    symbol
                ),

            signal =
                signal,

            score =
                result.score,

            confidence =
                result.confidence,

            pump =
                result.pump,

            dump =
                result.dump,

            moneyFlow =
                result.moneyFlow,

            news =
                result.news,

            trend =
                result.trend,

            finalAnalysis =
                result.finalAnalysis,

            lastAnalyzed =
                analyzedAt
        )
    )
}

fun remove(
    symbol: String
) {

    val normalized =
        normalizeSymbol(symbol)

    saveAll(
        getAll()
            .filterNot {
                it.symbol == normalized
            }
    )
}

fun clearAll() {

    preferences.edit()
        .remove(KEY_ANALYSIS)
        .apply()
}

private fun saveAll(
    items: List<WatchlistAnalysisSnapshot>
) {

    val array =
        JSONArray()

    items.forEach { item ->

        val json =
            JSONObject()

        json.put(
            "symbol",
            item.symbol
        )

        json.put(
            "signal",
            item.signal
        )

        json.put(
            "score",
            item.score
        )

        json.put(
            "confidence",
            item.confidence
        )

        json.put(
            "pump",
            item.pump
        )

        json.put(
            "dump",
            item.dump
        )

        json.put(
            "moneyFlow",
            item.moneyFlow
        )

        json.put(
            "news",
            item.news
        )

        json.put(
            "trend",
            item.trend
        )

        json.put(
            "finalAnalysis",
            item.finalAnalysis
        )

        json.put(
            "lastAnalyzed",
            item.lastAnalyzed
        )

        array.put(
            json
        )
    }

    preferences.edit()
        .putString(
            KEY_ANALYSIS,
            array.toString()
        )
        .apply()
}

private fun normalizeSymbol(
    value: String
): String {

    val symbol =
        value
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
            symbol.removeSuffix(
                "USD"
            ) + "USDT"

        else ->
            symbol + "USDT"
    }
}

companion object {

    private const val KEY_ANALYSIS =
        "watchlist_analysis"
}

}
