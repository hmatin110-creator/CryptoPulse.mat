package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET

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

    private val coinDeskApi =
        Retrofit.Builder()
            .baseUrl("https://www.coindesk.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(CoinDeskNewsApi::class.java)

    suspend fun load(symbol: String): NewsSnapshot =
        withContext(Dispatchers.IO) {

            val baseAsset = normalizeAsset(symbol)
            val aliases = buildAliases(baseAsset)

            val raw = runCatching {
                coinDeskApi.rss().string()
            }.getOrElse {
                return@withContext emptySnapshot()
            }

            if (raw.isBlank()) {
                return@withContext emptySnapshot()
            }

            val articles = parseRss(raw)

            if (articles.isEmpty()) {
                return@withContext emptySnapshot()
            }

            val relevant = articles
                .map { article ->
                    article.copy(
                        relevance = calculateRelevance(
                            title = article.title,
                            description = article.description,
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

            /*
             * اگر خبر کاملاً مخصوص یک ارز پیدا نشد،
             * از خبرهای عمومی بازار کریپتو استفاده می‌کنیم
             * تا بخش اخبار خالی نماند.
             */
            val selected =
                if (relevant.isNotEmpty()) {
                    relevant
                } else {
                    articles
                        .map { article ->
                            article.copy(
                                relevance = genericCryptoRelevance(
                                    article.title,
                                    article.description
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

    private fun normalizeAsset(symbol: String): String {
        var value = symbol.trim().uppercase()

        value = value
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

        if (asset.isBlank()) {
            return aliases
        }

        aliases += asset.lowercase()

        when (asset.uppercase()) {
            "BTC" -> {
                aliases += "bitcoin"
                aliases += "btc"
                aliases += "xbt"
            }

            "ETH" -> {
                aliases += "ethereum"
                aliases += "ether"
                aliases += "eth"
            }

            "BNB" -> {
                aliases += "bnb"
                aliases += "binance coin"
                aliases += "binance"
            }

            "SOL" -> {
                aliases += "solana"
                aliases += "sol"
            }

            "XRP" -> {
                aliases += "xrp"
                aliases += "ripple"
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

            "LINK" -> {
                aliases += "chainlink"
                aliases += "link"
            }

            "DOT" -> {
                aliases += "polkadot"
                aliases += "dot"
            }

            "MATIC" -> {
                aliases += "polygon"
                aliases += "matic"
            }

            "POL" -> {
                aliases += "polygon"
                aliases += "pol"
            }

            "LTC" -> {
                aliases += "litecoin"
                aliases += "ltc"
            }

            "BCH" -> {
                aliases += "bitcoin cash"
                aliases += "bch"
            }

            "UNI" -> {
                aliases += "uniswap"
                aliases += "uni"
            }

            "ATOM" -> {
                aliases += "cosmos"
                aliases += "atom"
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

            "SHIB" -> {
                aliases += "shiba inu"
                aliases += "shib"
            }

            "PEPE" -> {
                aliases += "pepe"
            }

            "FIL" -> {
                aliases += "filecoin"
                aliases += "fil"
            }

            "AAVE" -> {
                aliases += "aave"
            }

            "MKR" -> {
                aliases += "maker"
                aliases += "makerdao"
                aliases += "mkr"
            }

            "INJ" -> {
                aliases += "injective"
                aliases += "inj"
            }

            "RUNE" -> {
                aliases += "thorchain"
                aliases += "rune"
            }

            "IMX" -> {
                aliases += "immutable"
                aliases += "immutable x"
                aliases += "imx"
            }

            "SEI" -> {
                aliases += "sei"
            }

            "TIA" -> {
                aliases += "celestia"
                aliases += "tia"
            }

            "WIF" -> {
                aliases += "dogwifhat"
                aliases += "wif"
            }

            "BONK" -> {
                aliases += "bonk"
            }
        }

        return aliases
    }

    private fun parseRss(raw: String): List<NewsItem> {
        val result = mutableListOf<NewsItem>()

        return runCatching {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false

            val parser = factory.newPullParser()
            parser.setInput(raw.reader())

            var eventType = parser.eventType

            var insideItem = false
            var currentTag = ""

            var title = ""
            var link = ""
            var description = ""
            var pubDate = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {

                when (eventType) {

                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()

                        if (currentTag == "item") {
                            insideItem = true
                            title = ""
                            link = ""
                            description = ""
                            pubDate = ""
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (insideItem) {
                            val text = parser.text ?: ""

                            when (currentTag) {
                                "title" -> title += text
                                "link" -> link += text
                                "description" -> description += text
                                "pubdate" -> pubDate += text
                            }
                        }
                    }

                    XmlPullParser.CDSECT -> {
                        if (insideItem) {
                            val text = parser.text ?: ""

                            when (currentTag) {
                                "title" -> title += text
                                "link" -> link += text
                                "description" -> description += text
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val endTag = parser.name.lowercase()

                        if (endTag == "item" && insideItem) {
                            val cleanTitle = cleanHtml(title)
                            val cleanDescription = cleanHtml(description)
                            val cleanLink = cleanHtml(link)

                            if (cleanTitle.isNotBlank()) {
                                result += NewsItem(
                                    title = cleanTitle,
                                    source = "CoinDesk",
                                    url = cleanLink,
                                    sentiment = detectSentiment(
                                        "$cleanTitle $cleanDescription"
                                    ),
                                    relevance = 0.0
                                )
                            }

                            insideItem = false
                            currentTag = ""
                        }
                    }
                }

                eventType = parser.next()
            }

            result
        }.getOrElse {
            emptyList()
        }
    }

    private fun cleanHtml(value: String): String {
        return value
            .replace("<![CDATA[", "")
            .replace("]]>", "")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateRelevance(
        title: String,
        description: String,
        aliases: Set<String>
    ): Double {

        val titleText = title.lowercase()
        val descriptionText = description.lowercase()

        var score = 0.0

        for (alias in aliases) {
            if (titleText.contains(alias)) {
                score += 8.0
            }

            if (descriptionText.contains(alias)) {
                score += 3.0
            }
        }

        /*
         * خبرهایی که مستقیماً درباره ارز هستند
         * امتیاز خیلی بیشتری می‌گیرند.
         */
        if (score >= 8.0) {
            score += 10.0
        }

        return score
    }

    private fun genericCryptoRelevance(
        title: String,
        description: String
    ): Double {

        val text = "$title $description".lowercase()

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
            "fed",
            "interest rate",
            "sec",
            "etf"
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

        val bullishWords = listOf(
            "surge",
            "surges",
            "rally",
            "rallies",
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
            "inflows",
            "inflow",
            "growth",
            "strong",
            "positive",
            "optimistic",
            "buy",
            "buying",
            "accumulate",
            "accumulation"
        )

        val bearishWords = listOf(
            "drop",
            "drops",
            "fall",
            "falls",
            "falling",
            "decline",
            "declines",
            "bearish",
            "selloff",
            "sell-off",
            "crash",
            "plunge",
            "loss",
            "losses",
            "outflows",
            "outflow",
            "hack",
            "exploit",
            "lawsuit",
            "ban",
            "banned",
            "risk",
            "weak",
            "negative",
            "liquidation",
            "liquidations"
        )

        var bullish = 0
        var bearish = 0

        bullishWords.forEach {
            if (value.contains(it)) {
                bullish++
            }
        }

        bearishWords.forEach {
            if (value.contains(it)) {
                bearish++
            }
        }

        return when {
            bullish > bearish -> 100
            bearish > bullish -> -100
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

        items.forEach { item ->
            when {
                item.sentiment > 0 -> positive++
                item.sentiment < 0 -> negative++
            }
        }

        val total = items.size.toDouble()

        val positiveRatio = positive / total
        val negativeRatio = negative / total

        return (
            50.0 +
                positiveRatio * 35.0 -
                negativeRatio * 35.0
            )
            .toInt()
            .coerceIn(0, 100)
    }

    private fun calculateConfidence(
        totalArticles: Int,
        selectedArticles: Int
    ): Int {

        if (totalArticles <= 0 || selectedArticles <= 0) {
            return 0
        }

        var confidence = 40

        if (totalArticles >= 20) {
            confidence += 15
        } else if (totalArticles >= 10) {
            confidence += 10
        }

        if (selectedArticles >= 5) {
            confidence += 30
        } else if (selectedArticles >= 3) {
            confidence += 20
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

private interface CoinDeskNewsApi {

    @GET("arc/outboundfeeds/rss/")
    suspend fun rss(): ResponseBody
}
