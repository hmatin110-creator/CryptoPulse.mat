package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONArray
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

private val googleNewsApi =
    Retrofit.Builder()
        .baseUrl("https://news.google.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(GoogleNewsApi::class.java)

private val translationApi =
    Retrofit.Builder()
        .baseUrl("https://translate.googleapis.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(GoogleTranslateApi::class.java)

suspend fun load(symbol: String): NewsSnapshot =
    withContext(Dispatchers.IO) {
        val asset = normalizeAsset(symbol)
        val searchTerms = buildSearchTerms(asset)

        val raw = runCatching {
            googleNewsApi.search(
                query = searchTerms,
                language = "en-US",
                country = "US",
                edition = "US:en"
            ).string()
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
            .distinctBy {
                it.title.trim().lowercase()
            }
            .take(5)

        if (selected.isEmpty()) {
            return@withContext emptySnapshot()
        }

        /*
         * فقط عنوان خبر ترجمه می‌شود.
         * source و url همان مقادیر اصلی باقی می‌مانند.
         */
        val translated = coroutineScope {
            selected.map { item ->
                async(Dispatchers.IO) {
                    val translatedTitle = translateTitle(item.title)

                    item.copy(
                        title = translatedTitle.ifBlank {
                            item.title
                        }
                    )
                }
            }.awaitAll()
        }

        val score = calculateNewsScore(selected)

        val confidence = calculateConfidence(
            totalArticles = articles.size,
            selectedArticles = selected.size
        )

        NewsSnapshot(
            score = score,
            confidence = confidence,
            items = translated
        )
    }

private suspend fun translateTitle(title: String): String {
    if (title.isBlank()) return ""

    return runCatching {
        val response = translationApi.translate(
            client = "gtx",
            sourceLanguage = "en",
            targetLanguage = "fa",
            format = "text",
            text = title
        ).string()

        parseGoogleTranslation(response)
    }.getOrDefault("")
}

private fun parseGoogleTranslation(raw: String): String {
    if (raw.isBlank()) return ""

    return runCatching {
        val root = JSONArray(raw)
        val translations = root.optJSONArray(0)
            ?: return@runCatching ""

        val result = StringBuilder()

        for (i in 0 until translations.length()) {
            val part = translations.optJSONArray(i)
                ?: continue

            val translated = part.optString(0)

            if (translated.isNotBlank()) {
                if (result.isNotEmpty()) {
                    result.append(" ")
                }
                result.append(translated)
            }
        }

        cleanTranslatedText(result.toString())
    }.getOrDefault("")
}

private fun cleanTranslatedText(value: String): String {
    return value
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

private fun buildSearchTerms(asset: String): String {
    return when (asset) {
        "BTC" -> "Bitcoin crypto"
        "ETH" -> "Ethereum crypto"
        "BNB" -> "BNB Binance crypto"
        "SOL" -> "Solana crypto"
        "XRP" -> "XRP Ripple crypto"
        "ADA" -> "Cardano ADA crypto"
        "DOGE" -> "Dogecoin DOGE crypto"
        "TRX" -> "TRON TRX crypto"
        "AVAX" -> "Avalanche AVAX crypto"
        "LINK" -> "Chainlink LINK crypto"
        "DOT" -> "Polkadot DOT crypto"
        "MATIC", "POL" -> "Polygon MATIC POL crypto"
        "LTC" -> "Litecoin LTC crypto"
        "BCH" -> "Bitcoin Cash BCH crypto"
        "UNI" -> "Uniswap UNI crypto"
        "ATOM" -> "Cosmos ATOM crypto"
        "NEAR" -> "NEAR Protocol crypto"
        "APT" -> "Aptos APT crypto"
        "ARB" -> "Arbitrum ARB crypto"
        "OP" -> "Optimism OP crypto"
        "SUI" -> "Sui crypto"
        "TON" -> "Toncoin TON crypto"
        "SHIB" -> "Shiba Inu SHIB crypto"
        "PEPE" -> "PEPE crypto"
        "FIL" -> "Filecoin FIL crypto"
        "AAVE" -> "Aave crypto"
        "MKR" -> "Maker MKR crypto"
        "INJ" -> "Injective INJ crypto"
        "RUNE" -> "THORChain RUNE crypto"
        "IMX" -> "Immutable IMX crypto"
        "SEI" -> "SEI crypto"
        "TIA" -> "Celestia TIA crypto"
        "WIF" -> "dogwifhat WIF crypto"
        "BONK" -> "BONK crypto"
        else -> "$asset crypto"
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
            result += "btc"
        }

        "ETH" -> {
            result += "ethereum"
            result += "ether"
            result += "eth"
        }

        "BNB" -> {
            result += "bnb"
            result += "binance"
            result += "binance coin"
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

private fun calculateNewsScore(
    items: List<NewsItem>
): Int {
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

private interface GoogleTranslateApi {

@GET("translate_a/single")
suspend fun translate(
    @Query("client") client: String,
    @Query("sl") sourceLanguage: String,
    @Query("tl") targetLanguage: String,
    @Query("dt") format: String,
    @Query("q") text: String
): ResponseBody

}
