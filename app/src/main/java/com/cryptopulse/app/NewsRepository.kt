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

            if (baseAsset.isBlank()) {
                return@withContext neutralSnapshot()
            }

            val response =
                runCatching {
                    api.news(
                        lang = "EN",
                        limit = 50
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

            val root =
                runCatching {
                    JSONArray(raw)
                }.getOrElse {
                    return@withContext neutralSnapshot()
                }

            val aliases =
                buildAliases(baseAsset)

            val parsed =
                mutableListOf<NewsItem>()

            val seen =
                HashSet<String>()

            for (i in 0 until root.length()) {

                val obj =
                    root.optJSONObject(i)
                        ?: continue

                val title =
                    obj.optString("title")
                        .trim()

                if (title.isBlank()) {
                    continue
                }

                val source =
                    obj.optString("source")
                        .trim()
                        .ifBlank {
                            obj.optString("source_info")
                                .trim()
                        }

                val url =
                    obj.optString("url")
                        .trim()

                val publishedAt =
                    parsePublishedAt(obj)

                val normalizedTitle =
                    normalizeText(title)

                val titleKey =
                    normalizedTitle.lowercase(Locale.US)

                if (!seen.add(titleKey)) {
                    continue
                }

                val relevance =
                    calculateRelevance(
                        title = normalizedTitle,
                        aliases = aliases
                    )

                if (relevance <= 0) {
                    continue
                }

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

            val selected =
                parsed
                    .sortedWith(
                        compareByDescending<NewsItem> {
                            it.relevance
                        }.thenByDescending {
                            it.publishedAt
                        }
                    )
                    .take(20)

            calculateSnapshot(selected)
        }

    private fun calculateSnapshot(
        items: List<NewsItem>
    ): NewsSnapshot {

        if (items.isEmpty()) {
            return neutralSnapshot()
        }

        var positiveWeight = 0.0
        var negativeWeight = 0.0
        var totalWeight = 0.0

        for (item in items) {

            val weight =
                (
                    item.relevance.coerceIn(0, 100) / 100.0
                )

            when {
                item.sentiment > 0 -> {
                    positiveWeight +=
                        weight * item.sentiment
                }

                item.sentiment < 0 -> {
                    negativeWeight +=
                        weight * -item.sentiment
                }
            }

            totalWeight += weight
        }

        if (totalWeight <= 0.0) {
            return neutralSnapshot()
        }

        val positive =
            positiveWeight / totalWeight

        val negative =
            negativeWeight / totalWeight

        val rawScore =
            50.0 +
            positive * 50.0 -
            negative * 50.0

        val score =
            rawScore
                .coerceIn(0.0, 100.0)
                .toInt()

        val relevanceAverage =
            items
                .map { it.relevance }
                .average()

        val confidence =
            when {
                items.size >= 10 &&
                    relevanceAverage >= 75.0 ->
                    90

                items.size >= 6 &&
                    relevanceAverage >= 65.0 ->
                    80

                items.size >= 3 &&
                    relevanceAverage >= 55.0 ->
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

    /*
     * تطبیق دقیق‌تر نماد.
     *
     * برای BTC و ETH دیگر صرفاً وجود حروف
     * در عنوان کافی نیست.
     */
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
                alias.lowercase(Locale.US)

            if (clean.length <= 3) {

                if (
                    tokens.contains(clean)
                ) {
                    best =
                        maxOf(
                            best,
                            90
                        )
                }

                continue
            }

            if (
                tokens.contains(clean)
            ) {
                best =
                    maxOf(
                        best,
                        95
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

        /*
         * نام‌های عمومی اکوسیستم ارز.
         */
        val ecosystemTerms =
            setOf(
                "crypto",
                "cryptocurrency",
                "blockchain",
                "token",
                "coin",
                "defi",
                "web3",
                "altcoin"
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
                    65
                )
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
                aliases += "POLYX"
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
                aliases += "TON"
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

            "APT" -> {
                aliases += "APTOS"
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
                "etf inflow",
                "institutional buying"
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

        var positive = 0
        var negative = 0

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
                (35 + positive * 8)
                    .coerceAtMost(100)

            negative > positive ->
                -(35 + negative * 8)
                    .coerceAtMost(100)

            else ->
                0
        }
    }

    private fun parsePublishedAt(
        obj: JSONObject
    ): Long {

        val direct =
            obj.optLong(
                "published_on",
                0L
            )

        if (direct > 0L) {
            return direct * 1000L
        }

        val published =
            obj.optString(
                "publishedAt"
            )

        return runCatching {
            java.time.Instant
                .parse(published)
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
        @Query("limit") limit: Int
    ): ResponseBody
}
