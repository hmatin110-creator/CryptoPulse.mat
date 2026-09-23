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
    val lastAnalyzed: Long,

    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    val stopLoss: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val riskReward: Double? = null
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
                            ),

                        entryLow =
                            readDouble(
                                item,
                                "entryLow"
                            ),

                        entryHigh =
                            readDouble(
                                item,
                                "entryHigh"
                            ),

                        stopLoss =
                            readDouble(
                                item,
                                "stopLoss"
                            ),

                        tp1 =
                            readDouble(
                                item,
                                "tp1"
                            ),

                        tp2 =
                            readDouble(
                                item,
                                "tp2"
                            ),

                        riskReward =
                            readDouble(
                                item,
                                "riskReward"
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

        val plan =
            result.tradePlan

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
                    analyzedAt,

                entryLow =
                    plan?.entryLow,

                entryHigh =
                    plan?.entryHigh,

                stopLoss =
                    plan?.stopLoss,

                tp1 =
                    plan?.tp1,

                tp2 =
                    plan?.tp2,

                riskReward =
                    plan?.riskReward
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

            item.entryLow?.let {
                json.put(
                    "entryLow",
                    it
                )
            }

            item.entryHigh?.let {
                json.put(
                    "entryHigh",
                    it
                )
            }

            item.stopLoss?.let {
                json.put(
                    "stopLoss",
                    it
                )
            }

            item.tp1?.let {
                json.put(
                    "tp1",
                    it
                )
            }

            item.tp2?.let {
                json.put(
                    "tp2",
                    it
                )
            }

            item.riskReward?.let {
                json.put(
                    "riskReward",
                    it
                )
            }

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

    private fun readDouble(
        item: JSONObject,
        key: String
    ): Double? {

        if (!item.has(key)) {
            return null
        }

        return item
            .optDouble(
                key,
                Double.NaN
            )
            .takeIf {
                it.isFinite()
            }
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
