package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class NewsItem(
    val title: String,
    val source: String,
    val url: String,
    val sentiment: Int,
    val relevance: Double
)

data class NewsSnapshot(
    val score: Int,
    val confidence: Int,
    val items: List<NewsItem>
)

class NewsRepository {

    private val api =
        Retrofit.Builder()
            .baseUrl("https://min-api.cryptocompare.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(CryptoCompareNewsApi::class.java)

    suspend fun load(symbol: String): NewsSnapshot =
        withContext(Dispatchers.IO) {

            val asset = normalizeAsset(symbol)

            val raw = runCatching {
                api.news(
                    lang = "EN",
                    limit = 100
                ).string()
            }.getOrElse {
                return@withContext emptySnapshot()
            }

            if (raw.isBlank()) {
                return@withContext emptySnapshot()
            }

            val articles = parseArticles(raw)

            if (articles.isEmpty()) {
                return@withContext emptySnapshot()
            }

            val aliases = buildAliases(asset)

            val relevant = articles
                .map { item ->
                    item.copy(
                        relevance = calculateRelevance(
                            item = item,
                            aliases = aliases
                        )
                    )
                }
                .filter { it.relevance > 0.0 }
                .sortedWith(
                    compareByDescending<NewsItem> { it.relevance }
                        .thenByDescending { sentimentWeight(it.sentiment) }
                )
                .distinctBy {
                    it.title.trim().lowercase()
                }
                .take(5)

            val selected =
                if (relevant.isNotEmpty()) {
                    relevant
                } else {
                    articles
                        .map { item ->
                            item.copy(
                                relevance = genericRelevance(item)
                            )
                        }
                        .filter { it.relevance > 0.0 }
                        .sortedWith(
                            compareByDescending<NewsItem> { it.relevance }
                                .thenByDescending {
                                    sentimentWeight(it.sentiment)
                                }
                        )
                        .distinctBy {
                            it.title.trim().lowercase()
                        }
                        .take(5)
                }

            if (selected.isEmpty()) {
                return@withContext emptySnapshot()
            }

            val score = calculateNewsScore(selected)

            val confidence =
                if (relevant.isNotEmpty()) {
                    calculateConfidence(
                        totalArticles = articles.size,
                        selectedArticles = selected.size
                    )
                } else {
                    35
                }

            NewsSnapshot(
                score = score,
                confidence = confidence,
                items = selected
            )
        }

    private fun parseArticles(raw: String): List<NewsItem> {

        return runCatching {

            val root = JSONObject(raw)

            val data =
                root.optJSONArray("Data")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()

            val result = mutableListOf<NewsItem>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i)
                    ?: continue

                val title =
                    obj.optString("title")
                        .ifBlank {
                            obj.optString("TITLE")
                        }
                        .trim()

                if (title.isBlank()) {
                    continue
                }

                val source =
                    obj.optString("source")
                        .ifBlank {
                            obj.optString("SOURCE")
                        }
                        .ifBlank {
                            obj.optString("source_info")
                        }
                        .trim()

                val url =
                    obj.optString("url")
                        .ifBlank {
                            obj.optString("URL")
                        }
                        .trim()

                val description =
                    obj.optString("body")
                        .ifBlank {
                            obj.optString("BODY")
                        }
                        .ifBlank {
                            obj.optString("description")
                        }
                        .trim()

                val text = "$title $description"

                result += NewsItem(
                    title = title,
                    source = source.ifBlank { "CryptoCompare" },
                    url = url,
                    sentiment = detectSentiment(text),
                    relevance = 0.0
                )
            }

            result

        }.getOrElse {
            emptyList()
        }
    }

    private fun normalizeAsset(symbol: String): String {

        var value = symbol
            .trim()
            .uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")

        when {
            value.endsWith("USDT") ->
                value = value.removeSuffix("USDT")

            value.endsWith("USD") ->
                value = value.removeSuffix("USD")
        }

        return value
    }

    private fun buildAliases(asset: String): Set<String> {

        val result = mutableSetOf<String>()

        if (asset.isBlank()) {
            return result
        }

        result += asset.lowercase()

        when (asset) {

            "BTC" -> {
                result += "bitcoin"
                result += "btc"
            }

            "ETH" -> {
                result += "ethereum"
                result += "ether"
                result += "eth"
            }

            "BNB" -> {
                result += "bnb"
                result += "binance coin"
                result += "binance"
            }

            "SOL" -> {
                result += "solana"
                result += "sol"
            }

            "XRP" -> {
                result += "ripple"
                result += "xrp"
            }

            "ADA" -> {
                result += "cardano"
                result += "ada"
            }

            "DOGE" -> {
                result += "dogecoin"
                result += "doge"
            }

            "TRX" -> {
                result += "tron"
                result += "trx"
            }

            "AVAX" -> {
                result += "avalanche"
                result += "avax"
            }

            "LINK" -> {
                result += "chainlink"
                result += "link"
            }

            "DOT" -> {
                result += "polkadot"
                result += "dot"
            }

            "MATIC", "POL" -> {
                result += "polygon"
                result += "matic"
                result += "pol"
            }

            "LTC" -> {
                result += "litecoin"
                result += "ltc"
            }

            "BCH" -> {
                result += "bitcoin cash"
                result += "bch"
            }

            "UNI" -> {
                result += "uniswap"
                result += "uni"
            }

            "ATOM" -> {
                result += "cosmos"
                result += "atom"
            }

            "NEAR" -> {
                result += "near protocol"
                result += "near"
            }

            "APT" -> {
                result += "aptos"
                result += "apt"
            }

            "ARB" -> {
                result += "arbitrum"
                result += "arb"
            }

            "OP" -> {
                result += "optimism"
                result += "op"
            }

            "SUI" -> {
                result += "sui"
            }

            "TON" -> {
                result += "toncoin"
                result += "ton"
            }

            "SHIB" -> {
                result += "shiba inu"
                result += "shib"
            }

            "PEPE" -> {
                result += "pepe"
            }

            "FIL" -> {
                result += "filecoin"
                result += "fil"
            }

            "AAVE" -> {
                result += "aave"
            }

            "MKR" -> {
                result += "maker"
                result += "makerdao"
                result += "mkr"
            }

            "INJ" -> {
                result += "injective"
                result += "inj"
            }

            "RUNE" -> {
                result += "thorchain"
                result += "rune"
            }

            "IMX" -> {
                result += "immutable"
                result += "immutable x"
                result += "imx"
            }

            "SEI" -> {
                result += "sei"
            }

            "TIA" -> {
                result += "celestia"
                result += "tia"
            }

            "WIF" -> {
                result += "dogwifhat"
                result += "wif"
            }

            "BONK" -> {
                result += "bonk"
            }
        }

        return result
    }

    private fun calculateRelevance(
        item: NewsItem,
        aliases: Set<String>
    ): Double {

        val text = item.title.lowercase()

        var score = 0.0

        aliases.forEach { alias ->
            if (text.contains(alias)) {
                score += 10.0
            }
        }

        if (score >= 10.0) {
            score += 10.0
        }

        return score
    }

    private fun genericRelevance(item: NewsItem): Double {

        val text = item.title.lowercase()

        val keywords = listOf(
            "crypto",
            "cryptocurrency",
            "bitcoin",
            "ethereum",
            "blockchain",
            "digital asset",
            "digital assets",
            "token",
            "defi",
            "stablecoin",
            "exchange",
            "etf",
            "sec",
            "fed",
            "interest rate"
        )

        var score = 0.0

        keywords.forEach { keyword ->
            if (text.contains(keyword)) {
                score += 1.0
            }
        }

        return score
    }

    private fun detectSentiment(text: String): Int {

        val value = text.lowercase()

        val bullish = listOf(
            "surge",
            "rally",
            "rising",
            "rise",
            "gain",
            "gains",
            "bullish",
            "breakout",
            "record high",
            "all-time high",
            "adoption",
            "approval",
            "approved",
            "inflow",
            "inflows",
            "growth",
            "strong",
            "positive",
            "optimistic",
            "buy",
            "buying",
            "accumulate"
        )

        val bearish = listOf(
            "drop",
            "fall",
            "falling",
            "decline",
            "bearish",
            "selloff",
            "sell-off",
            "crash",
            "plunge",
            "loss",
            "losses",
            "outflow",
            "outflows",
            "hack",
            "exploit",
            "lawsuit",
            "ban",
            "banned",
            "risk",
            "weak",
            "negative",
            "liquidation"
        )

        var positiveCount = 0
        var negativeCount = 0

        bullish.forEach {
            if (value.contains(it)) {
                positiveCount++
            }
        }

        bearish.forEach {
            if (value.contains(it)) {
                negativeCount++
            }
        }

        return when {
            positiveCount > negativeCount -> 100
            negativeCount > positiveCount -> -100
            else -> 0
        }
    }

    private fun sentimentWeight(sentiment: Int): Int {
        return when {
            sentiment > 0 -> 2
            sentiment < 0 -> 1
            else -> 0
        }
    }

    private fun calculateNewsScore(
        items: List<NewsItem>
    ): Int {

        if (items.isEmpty()) {
            return 50
        }

        var positive = 0
        var negative = 0

        items.forEach {
            when {
                it.sentiment > 0 -> positive++
                it.sentiment < 0 -> negative++
            }
        }

        val total = items.size.toDouble()

        return (
            50.0 +
                (positive / total) * 35.0 -
                (negative / total) * 35.0
            )
            .toInt()
            .coerceIn(0, 100)
    }

    private fun calculateConfidence(
        totalArticles: Int,
        selectedArticles: Int
    ): Int {

        var confidence = 35

        if (totalArticles >= 20) {
            confidence += 20
        } else if (totalArticles >= 10) {
            confidence += 10
        }

        if (selectedArticles >= 5) {
            confidence += 35
        } else if (selectedArticles >= 3) {
            confidence += 25
        } else if (selectedArticles >= 1) {
            confidence += 10
        }

        return confidence.coerceIn(0, 100)
    }

    private fun emptySnapshot(): NewsSnapshot {
        return NewsSnapshot(
            score = 50,
            confidence = 0,
            items = emptyList()
        )
    }
}

private interface CryptoCompareNewsApi {

    @GET("data/v2/news/")
    suspend fun news(
        @Query("lang") lang: String,
        @Query("limit") limit: Int
    ): ResponseBody
}
