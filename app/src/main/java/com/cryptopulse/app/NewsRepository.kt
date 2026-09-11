package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**

News layer v0.4:

Uses publisher RSS/headline feeds; does not copy article bodies.


Scores each item by source credibility, recency, relevance, severity and sentiment.


Market-moving events (ETF, hack, exploit, lawsuit, regulation, listing, upgrade...) receive higher impact.
*/
data class NewsItem(
val source: String,
val title: String,
val link: String,
val published: String,
val sentiment: Int,       // -100..100
val impact: Int,          // 1..5
val credibility: Int,     // 0..100
val relevance: Int,       // 0..100
val recency: Int,         // 0..100
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
private data class Feed(val source: String, val url: String, val credibility: Int)

private val feeds = listOf(  
    Feed("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/", 94),  
    Feed("CryptoSlate", "https://cryptoslate.com/feed/", 88),  
    Feed("The Block", "https://www.theblock.co/rss.xml", 93),  
    Feed("Cointelegraph", "https://cointelegraph.com/rss", 84)  
)  

suspend fun load(symbol: String): NewsSnapshot = withContext(Dispatchers.IO) {  
    val aliases = aliasesFor(symbol)  
    val all = feeds.flatMap { feed ->  
        runCatching { parseFeed(feed, aliases) }.getOrDefault(emptyList())  
    }  
        .distinctBy { (it.source + "|" + it.title).lowercase() }  
        .sortedWith(compareByDescending<NewsItem> { it.impact * it.relevance * it.recency }.thenByDescending { it.published })  
        .take(30)  

    if (all.isEmpty()) return@withContext NewsSnapshot(emptyList(), 50, 0, 0, 0, 0)  

    // Source credibility and recency prevent one low-quality/old headline from dominating.  
    val weighted = all.map { item ->  
        val direction = item.sentiment / 100.0  
        val w = (item.credibility / 100.0) * (item.relevance / 100.0) *  
                (item.recency / 100.0) * item.impact  
        direction * w  
    }  
    val sumW = all.sumOf { item ->  
        (item.credibility / 100.0) * (item.relevance / 100.0) * (item.recency / 100.0) * item.impact  
    }.coerceAtLeast(0.001)  
    val normalized = weighted.sum() / sumW  
    val score = (50 + normalized * 50).roundToInt().coerceIn(0, 100)  

    val bullish = all.count { it.sentiment >= 35 }  
    val bearish = all.count { it.sentiment <= -35 }  
    val moving = all.count { it.impact >= 4 }  
    val sourceDiversity = all.map { it.source }.distinct().size  
    val agreement = maxOf(bullish, bearish).toDouble() / all.size  
    val confidence = (  
        45 +  
            minOf(20, all.size * 2) +  
            sourceDiversity * 5 +  
            (agreement * 20).roundToInt() +  
            if (moving > 0) 5 else 0  
        ).coerceIn(0, 98)  

    NewsSnapshot(all, score, confidence, bullish, bearish, moving)  
}  

private fun aliasesFor(symbol: String): Set<String> {  
    val base = symbol.removeSuffix("USDT").uppercase(Locale.US)  
    return when (base) {  
        "BTC" -> setOf("bitcoin", "btc")  
        "ETH" -> setOf("ethereum", "ether", "eth")  
        "SOL" -> setOf("solana", "sol")  
        "XRP" -> setOf("xrp", "ripple")  
        "DOGE" -> setOf("dogecoin", "doge")  
        else -> setOf(base.lowercase(Locale.US))  
    }  
}  

private fun parseFeed(feed: Feed, aliases: Set<String>): List<NewsItem> {  
    val conn = URL(feed.url).openConnection() as HttpURLConnection  
    conn.connectTimeout = 7000  
    conn.readTimeout = 7000  
    conn.setRequestProperty("User-Agent", "CryptoPulse/0.4 (+news reader)")  
    conn.connect()  
    conn.inputStream.use { input ->  
        val p = Xml.newPullParser()  
        p.setInput(input, "UTF-8")  
        val out = mutableListOf<NewsItem>()  
        var event = p.eventType  
        var inItem = false  
        var tag = ""  
        var title = ""  
        var link = ""  
        var date = ""  
        var desc = ""  

        while (event != XmlPullParser.END_DOCUMENT && out.size < 40) {  
            when (event) {  
                XmlPullParser.START_TAG -> {  
                    tag = p.name  
                    if (tag.equals("item", true) || tag.equals("entry", true)) {  
                        inItem = true; title = ""; link = ""; date = ""; desc = ""  
                    }  
                    // Atom links often store the URL in href rather than text.  
                    if (inItem && tag.equals("link", true) && link.isBlank()) {  
                        link = p.getAttributeValue(null, "href") ?: ""  
                    }  
                }  
                XmlPullParser.TEXT -> if (inItem) {  
                    val v = p.text.trim()  
                    when (tag.lowercase(Locale.US)) {  
                        "title" -> title = v  
                        "link" -> if (link.isBlank()) link = v  
                        "pubdate", "published", "updated", "dc:date" -> date = v  
                        "description", "summary" -> desc = v  
                    }  
                }  
                XmlPullParser.END_TAG -> if (inItem &&  
                    (p.name.equals("item", true) || p.name.equals("entry", true))) {  
                    val text = (title + " " + desc).lowercase(Locale.US)  
                    if (aliases.any { text.contains(it) }) {  
                        out += classify(feed, title, link, date)  
                    }  
                    inItem = false  
                }  
            }  
            event = p.next()  
        }  
        return out  
    }  
}  

private fun classify(feed: Feed, title: String, link: String, date: String): NewsItem {  
    val t = title.lowercase(Locale.US)  
    val positive = listOf(  
        "surge", "rally", "approval", "approved", "adoption", "inflow", "bullish",  
        "breakout", "partnership", "launch", "growth", "record", "upgrade", "etf",  
        "buyback", "institutional", "accumulation", "staking", "license", "listing",  
        "mainnet", "integration", "funding", "investment", "treasury"  
    )  
    val negative = listOf(  
        "hack", "exploit", "lawsuit", "ban", "outflow", "bearish", "crash", "slump",  
        "fraud", "scam", "liquidation", "warning", "delay", "breach", "attack",  
        "sell-off", "selloff", "shutdown", "sanction", "fine", "delist", "bankrupt",  
        "stolen", "vulnerability", "investigation", "rejected", "rejection"  
    )  
    val marketMoving = listOf(  
        "etf", "sec", "cftc", "regulation", "lawsuit", "hack", "exploit", "breach",  
        "approval", "approved", "rejected", "rejection", "listing", "delist", "mainnet",  
        "upgrade", "token unlock", "buyback", "acquisition", "bankruptcy", "sanction"  
    )  

    val pos = positive.count { t.contains(it) }  
    val neg = negative.count { t.contains(it) }  
    val raw = (pos - neg).coerceIn(-5, 5)  
    val sentiment = (raw / 5.0 * 100).roundToInt()  
    val severity = when {  
        marketMoving.count { t.contains(it) } >= 2 -> 5  
        marketMoving.any { t.contains(it) } -> 4  
        pos + neg >= 3 -> 3  
        pos + neg >= 1 -> 2  
        else -> 1  
    }  
    val category = when {  
        t.contains("etf") || t.contains("sec") || t.contains("regulation") -> "Regulation / ETF"  
        negative.any { t.contains(it) } -> "Risk"  
        positive.any { t.contains(it) } -> "Catalyst"  
        else -> "Market"  
    }  
    val hours = ageHours(date)  
    val recency = when {  
        hours <= 6 -> 100  
        hours <= 24 -> 90  
        hours <= 72 -> 72  
        hours <= 168 -> 45  
        else -> 20  
    }  
    val relevance = 80 // item already matched the coin aliases; title-level relevance is high.  
    return NewsItem(feed.source, title, link, date, sentiment, severity, feed.credibility, relevance, recency, category)  
}  

private fun ageHours(value: String): Long {  
    if (value.isBlank()) return 168  
    val now = Instant.now()  
    val instant = runCatching {  
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()  
    }.getOrElse {  
        runCatching { Instant.parse(value) }.getOrElse { return 168 }  
    }  
    return maxOf(0, ChronoUnit.HOURS.between(instant, now))  
}

}
