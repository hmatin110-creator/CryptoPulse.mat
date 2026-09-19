package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
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
.baseUrl("https://news.google.com/")
.addConverterFactory(MoshiConverterFactory.create())
.build()
.create(GoogleNewsApi::class.java)

suspend fun load(symbol: String): NewsSnapshot =
    withContext(Dispatchers.IO) {
        val asset = normalizeAsset(symbol)
        val searchTerms = buildSearchTerms(asset)

        val raw = runCatching {
            api.search(
                query = searchTerms,
                language = "fa",
                country = "IR",
                edition = "IR:fa"
            ).string()
        }.getOrElse {
            return@withContext emptySnapshot()
        }

        if (raw.isBlank()) return@withContext emptySnapshot()

        val articles = parseRss(raw)
        if (articles.isEmpty()) return@withContext emptySnapshot()

        val aliases = buildAliases(asset)

        val selected = articles
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
            .distinctBy { it.title.trim().lowercase() }
            .take(5)

        if (selected.isEmpty()) return@withContext emptySnapshot()

        val score = calculateNewsScore(selected)

        val confidence = calculateConfidence(
            totalArticles = articles.size,
            selectedArticles = selected.size
        )

        NewsSnapshot(
            score = score,
            confidence = confidence,
            items = selected
        )
    }

private fun buildSearchTerms(asset: String): String {
    return when (asset) {
        "BTC" -> "بیت کوین BTC ارز دیجیتال"
        "ETH" -> "اتریوم ETH ارز دیجیتال"
        "BNB" -> "بایننس کوین BNB ارز دیجیتال"
        "SOL" -> "سولانا SOL ارز دیجیتال"
        "XRP" -> "ریپل XRP ارز دیجیتال"
        "ADA" -> "کاردانو ADA ارز دیجیتال"
        "DOGE" -> "دوج کوین DOGE ارز دیجیتال"
        "TRX" -> "ترون TRX ارز دیجیتال"
        "AVAX" -> "آوالانچ AVAX ارز دیجیتال"
        "LINK" -> "چین لینک LINK ارز دیجیتال"
        "DOT" -> "پولکادات DOT ارز دیجیتال"
        "MATIC", "POL" -> "پالیگان MATIC POL ارز دیجیتال"
        "LTC" -> "لایت کوین LTC ارز دیجیتال"
        "BCH" -> "بیت کوین کش BCH ارز دیجیتال"
        "UNI" -> "یونی سواپ UNI ارز دیجیتال"
        "ATOM" -> "کازماس ATOM ارز دیجیتال"
        "NEAR" -> "نیر NEAR ارز دیجیتال"
        "APT" -> "آپتوس APT ارز دیجیتال"
        "ARB" -> "آربیتروم ARB ارز دیجیتال"
        "OP" -> "آپتیمیسم OP ارز دیجیتال"
        "SUI" -> "سوئی SUI ارز دیجیتال"
        "TON" -> "تون کوین TON ارز دیجیتال"
        "SHIB" -> "شیبا SHIB ارز دیجیتال"
        "PEPE" -> "پپه PEPE ارز دیجیتال"
        "FIL" -> "فایل کوین FIL ارز دیجیتال"
        "AAVE" -> "آوه AAVE ارز دیجیتال"
        "MKR" -> "میکر MKR ارز دیجیتال"
        "INJ" -> "اینجکتیو INJ ارز دیجیتال"
        "RUNE" -> "تورچین RUNE ارز دیجیتال"
        "IMX" -> "ایمیوتبل IMX ارز دیجیتال"
        "SEI" -> "سئی SEI ارز دیجیتال"
        "TIA" -> "سلستیا TIA ارز دیجیتال"
        "WIF" -> "داگ ویف هت WIF ارز دیجیتال"
        "BONK" -> "بونک BONK ارز دیجیتال"
        else -> "$asset ارز دیجیتال"
    }
}

private fun parseRss(raw: String): List<NewsItem> {
    val result = mutableListOf<NewsItem>()

    val itemRegex = Regex(
        pattern = "<item>(.*?)</item>",
        options = setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.DOT_MATCHES_ALL
        )
    )

    val titleRegex = Regex(
        "<title>(.*?)</title>",
        setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.DOT_MATCHES_ALL
        )
    )

    val linkRegex = Regex(
        "<link>(.*?)</link>",
        setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.DOT_MATCHES_ALL
        )
    )

    val sourceRegex = Regex(
        "<source[^>]*>(.*?)</source>",
        setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.DOT_MATCHES_ALL
        )
    )

    val descriptionRegex = Regex(
        "<description>(.*?)</description>",
        setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.DOT_MATCHES_ALL
        )
    )

    itemRegex.findAll(raw).forEach { match ->
        val block = match.groupValues.getOrNull(1)
            ?: return@forEach

        val title = titleRegex
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanText)
            .orEmpty()

        if (title.isBlank()) return@forEach

        val link = linkRegex
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanText)
            .orEmpty()

        val source = sourceRegex
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanText)
            .orEmpty()

        val description = descriptionRegex
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanText)
            .orEmpty()

        result += NewsItem(
            title = title,
            source = source.ifBlank { "Google News" },
            url = link,
            sentiment = detectSentiment("$title $description"),
            relevance = 0.0
        )
    }

    return result
}

private fun cleanText(value: String): String {
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
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
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
        value.endsWith("USDT") -> {
            value = value.removeSuffix("USDT")
        }

        value.endsWith("USD") -> {
            value = value.removeSuffix("USD")
        }
    }

    return value
}

private fun buildAliases(asset: String): Set<String> {
    val result = mutableSetOf<String>()

    if (asset.isBlank()) return result

    result += asset.lowercase()

    when (asset) {
        "BTC" -> {
            result += "bitcoin"
            result += "بیت کوین"
            result += "بیت‌کوین"
        }

        "ETH" -> {
            result += "ethereum"
            result += "ether"
            result += "اتریوم"
        }

        "BNB" -> {
            result += "bnb"
            result += "binance"
            result += "binance coin"
            result += "بایننس"
            result += "بایننس کوین"
        }

        "SOL" -> {
            result += "solana"
            result += "سولانا"
        }

        "XRP" -> {
            result += "ripple"
            result += "ریپل"
        }

        "ADA" -> {
            result += "cardano"
            result += "کاردانو"
        }

        "DOGE" -> {
            result += "dogecoin"
            result += "doge"
            result += "دوج کوین"
            result += "دوج‌کوین"
        }

        "TRX" -> {
            result += "tron"
            result += "ترون"
        }

        "AVAX" -> {
            result += "avalanche"
            result += "آوالانچ"
        }

        "LINK" -> {
            result += "chainlink"
            result += "چین لینک"
            result += "چین‌لینک"
        }

        "DOT" -> {
            result += "polkadot"
            result += "پولکادات"
        }

        "MATIC", "POL" -> {
            result += "polygon"
            result += "matic"
            result += "pol"
            result += "پالیگان"
        }

        "LTC" -> {
            result += "litecoin"
            result += "لایت کوین"
            result += "لایت‌کوین"
        }

        "BCH" -> {
            result += "bitcoin cash"
            result += "بیت کوین کش"
            result += "بیت‌کوین کش"
        }

        "UNI" -> {
            result += "uniswap"
            result += "یونی سواپ"
            result += "یونی‌سواپ"
        }

        "ATOM" -> {
            result += "cosmos"
            result += "کازماس"
        }

        "NEAR" -> {
            result += "near protocol"
            result += "near"
            result += "نیر"
        }

        "APT" -> {
            result += "aptos"
            result += "آپتوس"
        }

        "ARB" -> {
            result += "arbitrum"
            result += "آربیتروم"
        }

        "OP" -> {
            result += "optimism"
            result += "آپتیمیسم"
        }

        "SUI" -> {
            result += "sui"
            result += "سوئی"
        }

        "TON" -> {
            result += "toncoin"
            result += "ton"
            result += "تون کوین"
            result += "تون‌کوین"
        }

        "SHIB" -> {
            result += "shiba inu"
            result += "shib"
            result += "شیبا"
        }

        "PEPE" -> {
            result += "pepe"
            result += "پپه"
        }

        "FIL" -> {
            result += "filecoin"
            result += "فایل کوین"
            result += "فایل‌کوین"
        }

        "AAVE" -> {
            result += "aave"
            result += "آوه"
        }

        "MKR" -> {
            result += "maker"
            result += "makerdao"
            result += "mkr"
            result += "میکر"
        }

        "INJ" -> {
            result += "injective"
            result += "inj"
            result += "اینجکتیو"
        }

        "RUNE" -> {
            result += "thorchain"
            result += "rune"
            result += "تورچین"
        }

        "IMX" -> {
            result += "immutable"
            result += "immutable x"
            result += "imx"
            result += "ایمیوتبل"
        }

        "SEI" -> {
            result += "sei"
            result += "سئی"
        }

        "TIA" -> {
            result += "celestia"
            result += "tia"
            result += "سلستیا"
        }

        "WIF" -> {
            result += "dogwifhat"
            result += "wif"
            result += "داگ ویف هت"
        }

        "BONK" -> {
            result += "bonk"
            result += "بونک"
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
        "accumulate",

        "صعود",
        "صعودی",
        "رشد",
        "افزایش",
        "افزایش قیمت",
        "جهش",
        "رالی",
        "رکورد",
        "رکورد جدید",
        "شکست مقاومت",
        "ورود سرمایه",
        "ورود پول",
        "تایید",
        "پذیرش",
        "مثبت",
        "خوش‌بین",
        "خوش بین",
        "خرید",
        "تقاضا",
        "سود",
        "رونق"
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
        "liquidation",

        "سقوط",
        "سقوط قیمت",
        "کاهش",
        "کاهش قیمت",
        "افت",
        "ریزش",
        "نزولی",
        "فروش",
        "فشار فروش",
        "خروج سرمایه",
        "خروج پول",
        "هک",
        "حمله",
        "اکسپلویت",
        "ممنوع",
        "ممنوعیت",
        "ریسک",
        "ضعف",
        "ضعیف",
        "زیان",
        "ضرر",
        "لیکوییدیشن",
        "انحلال",
        "شکایت",
        "منفی",
        "اصلاح شدید"
    )

    var positive = 0
    var negative = 0

    bullish.forEach {
        if (value.contains(it)) {
            positive++
        }
    }

    bearish.forEach {
        if (value.contains(it)) {
            negative++
        }
    }

    return when {
        positive > negative -> 100
        negative > positive -> -100
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

private fun calculateNewsScore(items: List<NewsItem>): Int {
    if (items.isEmpty()) return 50

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

private interface GoogleNewsApi {
@GET("rss/search")
suspend fun search(
@Query("q") query: String,
@Query("hl") language: String,
@Query("gl") country: String,
@Query("ceid") edition: String
): ResponseBody
}
