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
    val sentiment: String,
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

            val baseAsset = normalizeAsset(symbol)

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

            val aliases = buildAliases(baseAsset)

            val scored = articles
                .mapNotNull { article ->
                    val relevance = calculateRelevance(
                        article = article,
                        aliases = aliases
                    )

                    if (relevance <= 0.0) {
                        null
                    } else {
                        article.copy(relevance = relevance)
                    }
                }
                .sortedWith(
                    compareByDescending<NewsItem> { it.relevance }
                        .thenByDescending { sentimentWeight(it.sentiment) }
                )
                .distinctBy { it.title.trim().lowercase() }
                .take(5)

            if (scored.isEmpty()) {
                return@withContext NewsSnapshot(
                    score = 50,
                    confidence = 0,
                    items = emptyList()
                )
            }

            val score = calculateNewsScore(scored)
            val confidence = calculateConfidence(
                totalArticles = articles.size,
                selectedArticles = scored.size,
                baseAsset = baseAsset
            )

            NewsSnapshot(
                score = score,
                confidence = confidence,
                items = scored
            )
        }

    private fun parseArticles(raw: String): List<NewsItem> {
        return runCatching {
            val root = JSONObject(raw)

            val data =
                root.optJSONArray("Data")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()

            val result = ArrayList<NewsItem>()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue

                val title =
                    item.optString("title")
                        .ifBlank {
                            item.optString("TITLE")
                        }
                        .trim()

                if (title.isBlank()) continue

                val source =
                    item.optString("source_info")
                        .ifBlank {
                            item.optString("source")
                        }
                        .ifBlank {
                            item.optString("SOURCE")
                        }
                        .trim()

                val url =
                    item.optString("url")
                        .ifBlank {
                            item.optString("URL")
                        }
                        .trim()

                val body =
                    item.optString("body")
                        .ifBlank {
                            item.optString("BODY")
                        }
                        .trim()

                val categories =
                    item.optString("categories")
                        .ifBlank {
                            item.optString("CATEGORY")
                        }
                        .trim()

                val tags =
                    item.optString("tags")
                        .ifBlank {
                            item.optString("TAGS")
                        }
                        .trim()

                val combined =
                    "$title $body $categories $tags"

                val sentiment =
                    detectSentiment(combined)

                result += NewsItem(
                    title = title,
                    source = source.ifBlank { "Crypto News" },
                    url = url,
                    sentiment = sentiment,
                    relevance = 0.0
                )
            }

            result
        }.getOrDefault(emptyList())
    }

    private fun normalizeAsset(symbol: String): String {
        var value = symbol
            .trim()
            .uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")

        if (value.endsWith("USDT")) {
            value = value.removeSuffix("USDT")
        } else if (value.endsWith("USD")) {
            value = value.removeSuffix("USD")
        }

        return value
    }

    private fun buildAliases(asset: String): Set<String> {
        val aliases = mutableSetOf<String>()

        if (asset.isBlank()) return aliases

        aliases += asset.lowercase()

        when (asset.uppercase()) {
            "BTC" -> {
                aliases += "bitcoin"
                aliases += "btc"
            }

            "ETH" -> {
                aliases += "ethereum"
                aliases += "ether"
                aliases += "eth"
            }

            "BNB" -> {
                aliases += "bnb"
                aliases += "binance coin"
                aliases += "bnb chain"
            }

            "SOL" -> {
                aliases += "solana"
                aliases += "sol"
            }

            "XRP" -> {
                aliases += "ripple"
                aliases += "xrp"
            }

            "ADA" -> {
                aliases += "cardano"
                aliases += "ada"
            }

            "DOGE" -> {
                aliases += "dogecoin"
                aliases += "doge"
            }

            "TRX" -> {
                aliases += "tron"
                aliases += "trx"
            }

            "AVAX" -> {
                aliases += "avalanche"
                aliases += "avax"
            }

            "DOT" -> {
                aliases += "polkadot"
                aliases += "dot"
            }

            "LINK" -> {
                aliases += "chainlink"
                aliases += "link"
            }

            "MATIC", "POL" -> {
                aliases += "polygon"
                aliases += "matic"
                aliases += "pol"
            }

            "SHIB" -> {
                aliases += "shiba inu"
                aliases += "shib"
            }

            "LTC" -> {
                aliases += "litecoin"
                aliases += "ltc"
            }

            "BCH" -> {
                aliases += "bitcoin cash"
                aliases += "bch"
            }

            "ATOM" -> {
                aliases += "cosmos"
                aliases += "atom"
            }

            "UNI" -> {
                aliases += "uniswap"
                aliases += "uni"
            }

            "NEAR" -> {
                aliases += "near protocol"
                aliases += "near"
            }

            "APT" -> {
                aliases += "aptos"
                aliases += "apt"
            }

            "ARB" -> {
                aliases += "arbitrum"
                aliases += "arb"
            }

            "OP" -> {
                aliases += "optimism"
                aliases += "op"
            }

            "SUI" -> {
                aliases += "sui"
            }

            "TON" -> {
                aliases += "toncoin"
                aliases += "ton"
            }

            "INJ" -> {
                aliases += "injective"
                aliases += "inj"
            }

            "FIL" -> {
                aliases += "filecoin"
                aliases += "fil"
            }

            "AAVE" -> {
                aliases += "aave"
            }

            "ETC" -> {
                aliases += "ethereum classic"
                aliases += "etc"
            }

            "XLM" -> {
                aliases += "stellar"
                aliases += "xlm"
            }

            "ALGO" -> {
                aliases += "algorand"
                aliases += "algo"
            }

            "VET" -> {
                aliases += "vechain"
                aliases += "vet"
            }
        }

        return aliases
    }

    private fun calculateRelevance(
        article: NewsItem,
        aliases: Set<String>
    ): Double {

        if (aliases.isEmpty()) return 0.0

        val text =
            article.title.lowercase() + " " +
            article.source.lowercase()

        var score = 0.0

        aliases.forEach { alias ->
            if (text.contains(alias)) {
                score += when {
                    article.title.lowercase().contains(alias) -> 10.0
                    else -> 4.0
                }
            }
        }

        /*
         * اگر خود خبر نام ارز را نداشت ولی در عنوان خبر
         * درباره بازار کریپتو بود، امتیاز پایه کمی می‌گیرد.
         * این باعث می‌شود برای ارزهای کوچک هم بخش اخبار کاملاً خالی نشود.
         */
        val cryptoKeywords = listOf(
            "crypto",
            "cryptocurrency",
            "bitcoin",
            "ethereum",
            "blockchain",
            "token",
            "coin",
            "defi",
            "exchange",
            "binance",
            "market"
        )

        val keywordHits =
            cryptoKeywords.count {
                text.contains(it)
            }

        score += keywordHits * 0.15

        return score
    }

    private fun detectSentiment(text: String): String {
        val lower = text.lowercase()

        val bullishWords = listOf(
            "surge",
            "rally",
            "bullish",
            "breakout",
            "soar",
            "rise",
            "rises",
            "rising",
            "gain",
            "gains",
            "growth",
            "positive",
            "adoption",
            "inflow",
            "record high",
            "all-time high",
            "approval",
            "approved",
            "partnership"
        )

        val bearishWords = listOf(
            "crash",
            "drop",
            "fall",
            "falls",
            "falling",
            "bearish",
            "selloff",
            "sell-off",
            "decline",
            "declines",
            "declining",
            "loss",
            "losses",
            "hack",
            "hacked",
            "exploit",
            "lawsuit",
            "ban",
            "banned",
            "outflow",
            "liquidation"
        )

        val bullish =
            bullishWords.count { lower.contains(it) }

        val bearish =
            bearishWords.count { lower.contains(it) }

        return when {
            bullish > bearish -> "مثبت"
            bearish > bullish -> "منفی"
            else -> "خنثی"
        }
    }

    private fun sentimentWeight(sentiment: String): Int {
        return when (sentiment) {
            "مثبت" -> 2
            "خنثی" -> 1
            "منفی" -> 0
            else -> 0
        }
    }

    private fun calculateNewsScore(
        items: List<NewsItem>
    ): Int {

        if (items.isEmpty()) return 50

        var positive = 0
        var negative = 0
        var neutral = 0

        items.forEach {
            when (it.sentiment) {
                "مثبت" -> positive++
                "منفی" -> negative++
                else -> neutral++
            }
        }

        val total = items.size.toDouble()

        val raw =
            50.0 +
                (positive / total) * 35.0 -
                (negative / total) * 35.0

        return raw
            .coerceIn(0.0, 100.0)
            .toInt()
    }

    private fun calculateConfidence(
        totalArticles: Int,
        selectedArticles: Int,
        baseAsset: String
    ): Int {

        if (selectedArticles == 0) return 0

        var confidence = 45

        if (totalArticles >= 20) {
            confidence += 15
        } else if (totalArticles >= 10) {
            confidence += 10
        }

        if (selectedArticles >= 5) {
            confidence += 25
        } else if (selectedArticles >= 3) {
            confidence += 15
        } else {
            confidence += 5
        }

        if (baseAsset.isNotBlank()) {
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
