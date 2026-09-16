package com.cryptopulse.app

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

data class NewsItem(
    val source: String,
    val title: String,
    val link: String,
    val published: String,
    val sentiment: Int,
    val impact: Int,
    val credibility: Int,
    val relevance: Int,
    val recency: Int,
    val category: String
)

data class NewsSnapshot(
    val items: List<NewsItem>,
    val score: Int,
    val confidence: Int,
    val bullishCount: Int,
    val bearishCount: Int,
    val marketMovingCount: Int
)

class NewsRepository {

    private data class Feed(
        val source: String,
        val url: String,
        val credibility: Int
    )

    private val feeds = listOf(
        Feed(
            "CoinDesk",
            "https://www.coindesk.com/arc/outboundfeeds/rss/",
            94
        ),
        Feed(
            "CryptoSlate",
            "https://cryptoslate.com/feed/",
            88
        ),
        Feed(
            "The Block",
            "https://www.theblock.co/rss.xml",
            93
        ),
        Feed(
            "Cointelegraph",
            "https://cointelegraph.com/rss",
            84
        )
    )

    suspend fun load(symbol: String): NewsSnapshot =
        withContext(Dispatchers.IO) {

            val aliases = aliasesFor(symbol)

            val collected = feeds.flatMap { feed ->
                runCatching {
                    parseFeed(feed, aliases)
                }.getOrDefault(emptyList())
            }

            val deduplicated =
                deduplicate(collected)
                    .filter { it.relevance >= 55 }
                    .sortedByDescending {
                        it.impact * it.relevance * it.recency
                    }
                    .take(24)

            if (deduplicated.isEmpty()) {
                return@withContext NewsSnapshot(
                    emptyList(),
                    50,
                    0,
                    0,
                    0,
                    0
                )
            }

            val weights = deduplicated.map { item ->
                (
                    item.credibility / 100.0 *
                        item.relevance / 100.0 *
                        item.recency / 100.0 *
                        item.impact
                    ).coerceAtLeast(0.01)
            }

            val weightedSentiment =
                deduplicated.mapIndexed { index, item ->
                    (item.sentiment / 100.0) * weights[index]
                }.sum()

            val totalWeight =
                weights.sum().coerceAtLeast(0.01)

            val normalized =
                (weightedSentiment / totalWeight)
                    .coerceIn(-1.0, 1.0)

            val score =
                (50 + normalized * 50)
                    .roundToInt()
                    .coerceIn(0, 100)

            val bullish =
                deduplicated.count { it.sentiment >= 35 }

            val bearish =
                deduplicated.count { it.sentiment <= -35 }

            val moving =
                deduplicated.count { it.impact >= 4 }

            val strongRelevant =
                deduplicated.count {
                    it.relevance >= 75 &&
                        it.impact >= 3
                }

            val sourceDiversity =
                deduplicated
                    .map { it.source }
                    .distinct()
                    .size

            val agreement =
                maxOf(bullish, bearish).toDouble() /
                    deduplicated.size.coerceAtLeast(1)

            val confidence =
                (
                    30 +
                        minOf(20, strongRelevant * 4) +
                        sourceDiversity * 5 +
                        (agreement * 20).roundToInt() +
                        if (moving > 0) 8 else 0
                    ).coerceIn(0, 96)

            NewsSnapshot(
                items = deduplicated,
                score = score,
                confidence = confidence,
                bullishCount = bullish,
                bearishCount = bearish,
                marketMovingCount = moving
            )
        }

    private fun aliasesFor(symbol: String): Set<String> {
        val base =
            symbol
                .removeSuffix("USDT")
                .uppercase(Locale.US)

        return when (base) {
            "BTC" -> setOf(
                "bitcoin",
                "bitcoin (btc)",
                "btc"
            )

            "ETH" -> setOf(
                "ethereum",
                "ethereum (eth)",
                "eth"
            )

            "SOL" -> setOf(
                "solana",
                "solana (sol)",
                "sol"
            )

            "XRP" -> setOf(
                "ripple",
                "xrp"
            )

            "DOGE" -> setOf(
                "dogecoin",
                "doge"
            )

            else -> setOf(
                base.lowercase(Locale.US)
            )
        }
    }

    private fun parseFeed(
        feed: Feed,
        aliases: Set<String>
    ): List<NewsItem> {

        val connection =
            URL(feed.url).openConnection() as HttpURLConnection

        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"

        connection.setRequestProperty(
            "User-Agent",
            "CryptoAnalysis/1.0 Android"
        )

        try {
            if (connection.responseCode !in 200..299) {
                return emptyList()
            }

            connection.inputStream.use { input ->

                val parser =
                    Xml.newPullParser()

                parser.setInput(input, "UTF-8")

                val result =
                    mutableListOf<NewsItem>()

                var event =
                    parser.eventType

                var inItem = false
                var currentTag = ""

                var title = ""
                var link = ""
                var date = ""
                var description = ""

                while (
                    event != XmlPullParser.END_DOCUMENT &&
                    result.size < 50
                ) {

                    when (event) {

                        XmlPullParser.START_TAG -> {

                            currentTag =
                                parser.name

                            if (
                                currentTag.equals("item", true) ||
                                currentTag.equals("entry", true)
                            ) {
                                inItem = true
                                title = ""
                                link = ""
                                date = ""
                                description = ""
                            }

                            if (
                                inItem &&
                                currentTag.equals("link", true)
                            ) {
                                val href =
                                    parser.getAttributeValue(
                                        null,
                                        "href"
                                    )

                                if (!href.isNullOrBlank()) {
                                    link = href
                                }
                            }
                        }

                        XmlPullParser.TEXT -> {

                            if (inItem) {

                                val value =
                                    parser.text
                                        ?.trim()
                                        .orEmpty()

                                when (
                                    currentTag
                                        .lowercase(Locale.US)
                                ) {

                                    "title" ->
                                        title = value

                                    "link" ->
                                        if (link.isBlank()) {
                                            link = value
                                        }

                                    "pubdate",
                                    "published",
                                    "updated",
                                    "date",
                                    "dc:date" ->
                                        date = value

                                    "description",
                                    "summary" ->
                                        description = value
                                }
                            }
                        }

                        XmlPullParser.END_TAG -> {

                            if (
                                inItem &&
                                (
                                    parser.name.equals(
                                        "item",
                                        true
                                    ) ||
                                        parser.name.equals(
                                            "entry",
                                            true
                                        )
                                    )
                            ) {

                                val relevance =
                                    calculateRelevance(
                                        title,
                                        description,
                                        aliases
                                    )

                                if (
                                    relevance >= 55 &&
                                    title.isNotBlank()
                                ) {
                                    result += classify(
                                        feed,
                                        title,
                                        description,
                                        link,
                                        date,
                                        relevance
                                    )
                                }

                                inItem = false
                            }
                        }
                    }

                    event = parser.next()
                }

                return result
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun calculateRelevance(
        title: String,
        description: String,
        aliases: Set<String>
    ): Int {

        val titleText =
            normalizeText(title)

        val descriptionText =
            normalizeText(description)

        var score = 0

        aliases.forEach { alias ->

            val normalizedAlias =
                normalizeText(alias)

            if (
                containsCryptoTerm(
                    titleText,
                    normalizedAlias
                )
            ) {
                score += 70
            } else if (
                containsCryptoTerm(
                    descriptionText,
                    normalizedAlias
                )
            ) {
                score += 35
            }
        }

        val strongEventWords =
            listOf(
                "etf",
                "hack",
                "exploit",
                "listing",
                "delisting",
                "upgrade",
                "mainnet",
                "token unlock",
                "lawsuit",
                "regulation",
                "approval",
                "rejection"
            )

        if (
            strongEventWords.any {
                titleText.contains(it)
            }
        ) {
            score += 10
        }

        return score.coerceIn(0, 100)
    }

    private fun containsCryptoTerm(
        text: String,
        term: String
    ): Boolean {

        if (term.length <= 3) {
            return Regex(
                "(^|[^a-z0-9])" +
                    Regex.escape(term) +
                    "([^a-z0-9]|$)"
            ).containsMatchIn(text)
        }

        return text.contains(term)
    }

    private fun classify(
        feed: Feed,
        title: String,
        description: String,
        link: String,
        date: String,
        relevance: Int
    ): NewsItem {

        val text =
            normalizeText(
                "$title $description"
            )

        val positive =
            listOf(
                "surge",
                "rally",
                "adoption",
                "inflow",
                "bullish",
                "breakout",
                "partnership",
                "launch",
                "growth",
                "record",
                "upgrade",
                "buyback",
                "institutional",
                "accumulation",
                "staking",
                "license",
                "listing",
                "mainnet",
                "integration",
                "funding",
                "investment",
                "treasury",
                "approved",
                "approval"
            )

        val negative =
            listOf(
                "hack",
                "exploit",
                "lawsuit",
                "ban",
                "outflow",
                "bearish",
                "crash",
                "slump",
                "fraud",
                "scam",
                "liquidation",
                "warning",
                "delay",
                "breach",
                "attack",
                "sell-off",
                "selloff",
                "shutdown",
                "sanction",
                "fine",
                "delist",
                "bankrupt",
                "stolen",
                "vulnerability",
                "investigation",
                "rejected",
                "rejection"
            )

        val marketMoving =
            listOf(
                "etf",
                "sec",
                "cftc",
                "regulation",
                "lawsuit",
                "hack",
                "exploit",
                "breach",
                "approval",
                "approved",
                "rejected",
                "rejection",
                "listing",
                "delist",
                "mainnet",
                "upgrade",
                "token unlock",
                "buyback",
                "acquisition",
                "bankruptcy",
                "sanction"
            )

        val positiveCount =
            positive.count {
                text.contains(it)
            }

        val negativeCount =
            negative.count {
                text.contains(it)
            }

        var raw =
            positiveCount - negativeCount

        if (
            text.contains("etf rejected") ||
            text.contains("etf denied") ||
            text.contains("etf rejection")
        ) {
            raw -= 3
        }

        val sentiment =
            (raw.coerceIn(-5, 5) * 20)
                .coerceIn(-100, 100)

        val eventCount =
            marketMoving.count {
                text.contains(it)
            }

        val impact =
            when {
                eventCount >= 2 -> 5
                eventCount == 1 -> 4
                positiveCount + negativeCount >= 3 -> 3
                positiveCount + negativeCount >= 1 -> 2
                else -> 1
            }

        val category =
            when {

                text.contains("hack") ||
                    text.contains("exploit") ||
                    text.contains("breach") ->
                    "Security"

                text.contains("etf") ||
                    text.contains("sec") ||
                    text.contains("regulation") ->
                    "Regulation / ETF"

                text.contains("listing") ||
                    text.contains("delist") ->
                    "Listing"

                text.contains("upgrade") ||
                    text.contains("mainnet") ->
                    "Technology"

                sentiment >= 35 ->
                    "Positive"

                sentiment <= -35 ->
                    "Risk"

                else ->
                    "Neutral"
            }

        val recency =
            calculateRecency(date)

        return NewsItem(
            source = feed.source,
            title = title.trim(),
            link = link.trim(),
            published = date.trim(),
            sentiment = sentiment,
            impact = impact,
            credibility = feed.credibility,
            relevance = relevance,
            recency = recency,
            category = category
        )
    }

    private fun deduplicate(
        items: List<NewsItem>
    ): List<NewsItem> {

        val result =
            mutableListOf<NewsItem>()

        for (item in items) {

            val normalizedTitle =
                normalizeText(item.title)
                    .replace(
                        Regex("[^a-z0-9 ]"),
                        ""
                    )

            val duplicate =
                result.any { existing ->

                    val other =
                        normalizeText(existing.title)
                            .replace(
                                Regex("[^a-z0-9 ]"),
                                ""
                            )

                    similarity(
                        normalizedTitle,
                        other
                    ) >= 0.78
                }

            if (!duplicate) {
                result += item
            } else {

                val index =
                    result.indexOfFirst { existing ->
                        similarity(
                            normalizedTitle,
                            normalizeText(
                                existing.title
                            )
                        ) >= 0.78
                    }

                if (
                    index >= 0 &&
                    item.credibility >
                    result[index].credibility
                ) {
                    result[index] = item
                }
            }
        }

        return result
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {

        if (a.isBlank() || b.isBlank()) {
            return 0.0
        }

        val aa =
            a.split(" ")
                .filter { it.length > 2 }
                .toSet()

        val bb =
            b.split(" ")
                .filter { it.length > 2 }
                .toSet()

        if (aa.isEmpty() || bb.isEmpty()) {
            return 0.0
        }

        return aa.intersect(bb).size.toDouble() /
            aa.union(bb).size.toDouble()
    }

    private fun normalizeText(
        value: String
    ): String =
        value
            .lowercase(Locale.US)
            .replace("&amp;", "&")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun calculateRecency(
        value: String
    ): Int {

        if (value.isBlank()) return 10

        val instant =
            runCatching {
                ZonedDateTime
                    .parse(
                        value,
                        DateTimeFormatter.RFC_1123_DATE_TIME
                    )
                    .toInstant()
            }.getOrElse {

                runCatching {
                    Instant.parse(value)
                }.getOrElse {
                    return 10
                }
            }

        val hours =
            maxOf(
                0,
                ChronoUnit.HOURS.between(
                    instant,
                    Instant.now()
                )
            )

        return when {
            hours <= 6 -> 100
            hours <= 24 -> 90
            hours <= 72 -> 72
            hours <= 168 -> 45
            hours <= 336 -> 25
            else -> 10
        }
    }
}
