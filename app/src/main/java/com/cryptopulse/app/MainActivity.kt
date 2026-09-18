package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import okhttp3.ResponseBody
import java.util.Locale

data class NewsSnapshot(
    val score: Int,
    val confidence: Int,
    val items: List<NewsItem> = emptyList()
)

data class NewsItem(
    val title: String,
    val source: String,
    val url: String = "",
    val publishedAt: Long = 0L,
    val relevance: Int = 0,
    val sentiment: Int = 0
)

class NewsRepository {

    private val api =
        Retrofit.Builder()
            .baseUrl("https://min-api.cryptocompare.com/")
            .addConverterFactory(
                MoshiConverterFactory.create()
            )
            .build()
            .create(CryptoCompareNewsApi::class.java)

    suspend fun load(
        symbol: String
    ): NewsSnapshot =
        withContext(Dispatchers.IO) {

            val cleanSymbol =
                normalizeSymbol(symbol)

            val baseAsset =
                cleanSymbol
                    .removeSuffix("USDT")
                    .removeSuffix("USD")
                    .trim()

            if (baseAsset.isBlank()) {
                return@withContext neutralSnapshot()
            }

            val response =
                runCatching {
                    api.news(
                        lang = "EN",
                        limit = 100,
                        categories = baseAsset
                    )
                }.getOrElse {
                    return@withContext neutralSnapshot()
                }

            val raw =
                runCatching {
                    response.string()
                }.getOrDefault("")

            if (raw.isBlank()) {
                return@withContext neutralSnapshot()
            }

            val articles =
                parseArticles(raw)

            if (articles.isEmpty()) {
                return@withContext neutralSnapshot()
            }

            val aliases =
                buildAliases(baseAsset)

            val parsed =
                mutableListOf<NewsItem>()

            val seen =
                HashSet<String>()

            for (obj in articles) {

                val title =
                    obj.optString("title")
                        .trim()

                if (title.isBlank()) {
                    continue
                }

                val normalizedTitle =
                    normalizeText(title)

                val titleKey =
                    normalizedTitle.lowercase(Locale.US)

                if (!seen.add(titleKey)) {
                    continue
                }

                val source =
                    obj.optString("source")
                        .trim()
                        .ifBlank {
                            obj.optString("source_info")
                                .trim()
                        }
                        .ifBlank {
                            "CryptoCompare"
                        }

                val url =
                    obj.optString("url")
                        .trim()

                val publishedAt =
                    parsePublishedAt(obj)

                val relevance =
                    calculateRelevance(
                        title = normalizedTitle,
                        aliases = aliases
                    )

                val sentiment =
                    calculateSentiment(
                        normalizedTitle
                    )

                parsed +=
                    NewsItem(
                        title = title,
                        source = source,
                        url = url,
                        publishedAt = publishedAt,
                        relevance = relevance,
                        sentiment = sentiment
                    )
            }

            if (parsed.isEmpty()) {
                return@withContext neutralSnapshot()
            }

            /*
             * مهم‌ترین خبرها:
             * 1. ارتباط با ارز
             * 2. شدت خبر
             * 3. جدیدتر بودن
             */
            val selected =
                parsed
                    .sortedWith(
                        compareByDescending<NewsItem> {
                            newsImportance(it)
                        }.thenByDescending {
                            it.publishedAt
                        }
                    )
                    .take(5)

            if (selected.isEmpty()) {
                return@withContext neutralSnapshot()
            }

            calculateSnapshot(selected)
        }

    private fun parseArticles(
        raw: String
    ): List<JSONObject> {

        val result =
            mutableListOf<JSONObject>()

        /*
         * CryptoCompare معمولاً پاسخ را به شکل:
         *
         * {
         *   "Type": 100,
         *   "Message": "...",
         *   "Data": [...]
         * }
         *
         * برمی‌گرداند.
         */

        val root =
            runCatching {
                JSONObject(raw)
            }.getOrElse {
                return emptyList()
            }

        val data =
            root.optJSONArray("Data")
                ?: root.optJSONArray("data")
                ?: return emptyList()

        for (i in 0 until data.length()) {
            val item =
                data.optJSONObject(i)
                    ?: continue

            result += item
        }

        return result
    }

    private fun newsImportance(
        item: NewsItem
    ): Double {

        val relevance =
            item.relevance.coerceIn(0, 100)

        val sentimentStrength =
            kotlin.math.abs(
                item.sentiment
            ).coerceIn(0, 100)

        val ageBonus =
            if (item.publishedAt > 0L) {
                val ageHours =
                    (
                        System.currentTimeMillis() -
                            item.publishedAt
                    )
                        .coerceAtLeast(0L)
                        .toDouble() /
                        3_600_000.0

                when {
                    ageHours <= 6.0 -> 20.0
                    ageHours <= 24.0 -> 15.0
                    ageHours <= 72.0 -> 8.0
                    else -> 0.0
                }
            } else {
                0.0
            }

        return relevance * 0.65 +
            sentimentStrength * 0.25 +
            ageBonus
    }

    private fun calculateSnapshot(
        items: List<NewsItem>
    ): NewsSnapshot {

        if (items.isEmpty()) {
            return neutralSnapshot()
        }

        var weightedSentiment =
            0.0

        var totalWeight =
            0.0

        for (item in items) {

            val relevanceWeight =
                (
                    item.relevance
                        .coerceIn(0, 100)
                        .toDouble() / 100.0
                )

            val freshnessWeight =
                if (item.publishedAt > 0L) {
                    val ageHours =
                        (
                            System.currentTimeMillis() -
                                item.publishedAt
                        )
                            .coerceAtLeast(0L)
                            .toDouble() /
                            3_600_000.0

                    when {
                        ageHours <= 6.0 -> 1.0
                        ageHours <= 24.0 -> 0.9
                        ageHours <= 72.0 -> 0.75
                        ageHours <= 168.0 -> 0.55
                        else -> 0.35
                    }
                } else {
                    0.5
                }

            val weight =
                (
                    relevanceWeight *
                        freshnessWeight
                ).coerceAtLeast(0.1)

            weightedSentiment +=
                item.sentiment * weight

            totalWeight +=
                weight
        }

        if (totalWeight <= 0.0) {
            return NewsSnapshot(
                score = 50,
                confidence = 40,
                items = items
            )
        }

        val averageSentiment =
            weightedSentiment /
                totalWeight

        /*
         * sentiment معمولاً بین -100 تا +100 است.
         * تبدیل به امتیاز 0 تا 100:
         *
         * -100 => 0
         *    0 => 50
         * +100 => 100
         */
        val score =
            (
                50.0 +
                    averageSentiment * 0.5
            )
                .coerceIn(0.0, 100.0)
                .toInt()

        val averageRelevance =
            items
                .map {
                    it.relevance
                }
                .average()

        val confidence =
            when {
                items.size >= 5 &&
                    averageRelevance >= 75.0 ->
                    90

                items.size >= 5 &&
                    averageRelevance >= 55.0 ->
                    80

                items.size >= 3 &&
                    averageRelevance >= 50.0 ->
                    70

                items.size >= 1 ->
                    55

                else ->
                    40
            }

        return NewsSnapshot(
            score = score,
            confidence = confidence,
            items = items
        )
    }

    private fun calculateRelevance(
        title: String,
        aliases: Set<String>
    ): Int {

        val normalized =
            normalizeText(title)

        val tokens =
            normalized
                .split(
                    Regex("[^a-z0-9]+")
                )
                .filter {
                    it.isNotBlank()
                }
                .toSet()

        var best =
            0

        for (alias in aliases) {

            val clean =
                alias
                    .lowercase(Locale.US)
                    .trim()

            if (clean.isBlank()) {
                continue
            }

            if (clean.length <= 3) {

                if (tokens.contains(clean)) {
                    best =
                        maxOf(
                            best,
                            95
                        )
                }

                continue
            }

            if (tokens.contains(clean)) {
                best =
                    maxOf(
                        best,
                        100
                    )

                continue
            }

            if (
                normalized.contains(
                    " $clean "
                )
            ) {
                best =
                    maxOf(
                        best,
                        90
                    )
            }
        }

        val ecosystemTerms =
            setOf(
                "crypto",
                "cryptocurrency",
                "blockchain",
                "token",
                "coin",
                "defi",
                "web3",
                "altcoin",
                "digital asset",
                "digital assets"
            )

        val hasEcosystem =
            tokens.any {
                ecosystemTerms.contains(it)
            }

        if (
            hasEcosystem &&
            best > 0
        ) {
            best =
                maxOf(
                    best,
                    70
                )
        }

        /*
         * اگر API خبر را مستقیماً برای همان دسته
         * ارز برگردانده باشد، حداقل ارتباط پایه را حفظ می‌کنیم.
         */
        if (
            best == 0 &&
            hasEcosystem
        ) {
            best = 45
        }

        return best.coerceIn(
            0,
            100
        )
    }

    private fun buildAliases(
        asset: String
    ): Set<String> {

        val aliases =
            mutableSetOf<String>()

        val clean =
            asset
                .trim()
                .uppercase()

        if (clean.isBlank()) {
            return emptySet()
        }

        aliases += clean

        when (clean) {

            "BTC" -> {
                aliases += "BITCOIN"
            }

            "ETH" -> {
                aliases += "ETHEREUM"
                aliases += "ETHER"
            }

            "BNB" -> {
                aliases += "BINANCE"
                aliases += "BINANCE COIN"
            }

            "SOL" -> {
                aliases += "SOLANA"
            }

            "XRP" -> {
                aliases += "RIPPLE"
            }

            "ADA" -> {
                aliases += "CARDANO"
            }

            "DOGE" -> {
                aliases += "DOGECOIN"
            }

            "TRX" -> {
                aliases += "TRON"
            }

            "AVAX" -> {
                aliases += "AVALANCHE"
            }

            "DOT" -> {
                aliases += "POLKADOT"
            }

            "LINK" -> {
                aliases += "CHAINLINK"
            }

            "MATIC" -> {
                aliases += "POLYGON"
            }

            "POL" -> {
                aliases += "POLYGON"
            }

            "LTC" -> {
                aliases += "LITECOIN"
            }

            "ATOM" -> {
                aliases += "COSMOS"
            }

            "UNI" -> {
                aliases += "UNISWAP"
            }

            "NEAR" -> {
                aliases += "NEAR"
                aliases += "NEAR PROTOCOL"
            }

            "APT" -> {
                aliases += "APTOS"
            }

            "ARB" -> {
                aliases += "ARBITRUM"
            }

            "OP" -> {
                aliases += "OPTIMISM"
            }

            "SUI" -> {
                aliases += "SUI"
            }

            "TON" -> {
                aliases += "TONCOIN"
            }

            "SHIB" -> {
                aliases += "SHIBA"
                aliases += "SHIBA INU"
            }

            "PEPE" -> {
                aliases += "PEPE"
            }

            "FIL" -> {
                aliases += "FILECOIN"
            }

            "ICP" -> {
                aliases += "INTERNET COMPUTER"
            }

            "ETC" -> {
                aliases += "ETHEREUM CLASSIC"
            }

            "XLM" -> {
                aliases += "STELLAR"
            }

            "HBAR" -> {
                aliases += "HEDERA"
            }

            "CRO" -> {
                aliases += "CRONOS"
            }

            "MKR" -> {
                aliases += "MAKER"
            }

            "AAVE" -> {
                aliases += "AAVE"
            }

            "RUNE" -> {
                aliases += "THORCHAIN"
            }

            "INJ" -> {
                aliases += "INJECTIVE"
            }

            "IMX" -> {
                aliases += "IMMUTABLE"
            }

            "SEI" -> {
                aliases += "SEI"
            }

            "TIA" -> {
                aliases += "CELESTIA"
            }

            "JUP" -> {
                aliases += "JUPITER"
            }
        }

        return aliases
            .map {
                normalizeText(it)
            }
            .filter {
                it.isNotBlank()
            }
            .toSet()
    }

    private fun calculateSentiment(
        title: String
    ): Int {

        val positiveWords =
            setOf(
                "surge",
                "surges",
                "rally",
                "rallies",
                "bullish",
                "bull",
                "gain",
                "gains",
                "rise",
                "rises",
                "rising",
                "breakout",
                "breaks out",
                "record high",
                "all time high",
                "ath",
                "approval",
                "approved",
                "adoption",
                "inflow",
                "inflows",
                "buying",
                "accumulation",
                "partnership",
                "launch",
                "growth",
                "strong",
                "positive",
                "optimistic",
                "institutional buying",
                "institutional adoption",
                "etf approval",
                "etf inflow"
            )

        val negativeWords =
            setOf(
                "crash",
                "crashes",
                "collapse",
                "collapses",
                "bearish",
                "bear",
                "fall",
                "falls",
                "falling",
                "drop",
                "drops",
                "dump",
                "selloff",
                "selling",
                "outflow",
                "outflows",
                "hack",
                "hacked",
                "exploit",
                "exploited",
                "lawsuit",
                "ban",
                "banned",
                "regulation crackdown",
                "fraud",
                "scam",
                "liquidation",
                "liquidations",
                "bankrupt",
                "bankruptcy",
                "negative",
                "warning",
                "risk",
                "risks",
                "etf outflow",
                "delisting"
            )

        var positive =
            0

        var negative =
            0

        for (word in positiveWords) {
            if (title.contains(word)) {
                positive++
            }
        }

        for (word in negativeWords) {
            if (title.contains(word)) {
                negative++
            }
        }

        return when {

            positive > negative ->
                (
                    35 +
                        positive * 10
                    )
                    .coerceAtMost(100)

            negative > positive ->
                -(
                    35 +
                        negative * 10
                    )
                    .coerceAtMost(100)

            else ->
                0
        }
    }

    private fun parsePublishedAt(
        obj: JSONObject
    ): Long {

        val publishedOn =
            obj.optLong(
                "published_on",
                0L
            )

        if (publishedOn > 0L) {
            return publishedOn * 1000L
        }

        val publishedAt =
            obj.optString(
                "publishedAt"
            )
                .trim()

        if (publishedAt.isBlank()) {
            return 0L
        }

        return runCatching {
            java.time.Instant
                .parse(publishedAt)
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun normalizeText(
        text: String
    ): String {

        return text
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()
            .replace(
                Regex("\\s+"),
                " "
            )
    }

    private fun normalizeSymbol(
        input: String
    ): String {

        val symbol =
            input
                .trim()
                .uppercase()
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")

        return when {

            symbol.endsWith("USDT") ->
                symbol

            symbol.endsWith("USD") ->
                symbol.removeSuffix("USD") +
                    "USDT"

            else ->
                symbol + "USDT"
        }
    }

    private fun neutralSnapshot():
        NewsSnapshot {

        return NewsSnapshot(
            score = 50,
            confidence = 40,
            items = emptyList()
        )
    }
}

private interface CryptoCompareNewsApi {

    @GET("data/v2/news/")
    suspend fun news(
        @Query("lang") lang: String,
        @Query("limit") limit: Int,
        @Query("categories") categories: String
    ): ResponseBody
}
