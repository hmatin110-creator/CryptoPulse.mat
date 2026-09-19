package com.cryptopulse.app

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class NewsItem(
    val title: String,
    val source: String,
    val link: String,
    val description: String = "",
    val sentiment: Int = 0
)

data class NewsSnapshot(
    val score: Int,
    val confidence: Int,
    val items: List<NewsItem>
)

class NewsRepository {

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request()
            .newBuilder()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "*/*")
            .build()

        chain.proceed(request)
    }

    private val httpClient =
        OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private val newsApi =
        Retrofit.Builder()
            .baseUrl("https://news.google.com/")
            .client(httpClient)
            .build()
            .create(GoogleNewsApi::class.java)

    private val googleTranslateApi =
        Retrofit.Builder()
            .baseUrl("https://translate.googleapis.com/")
            .client(httpClient)
            .build()
            .create(GoogleTranslateApi::class.java)

    private val googleTranslateWebApi =
        Retrofit.Builder()
            .baseUrl("https://translate.google.com/")
            .client(httpClient)
            .build()
            .create(GoogleTranslateApi::class.java)

    private val myMemoryApi =
        Retrofit.Builder()
            .baseUrl("https://api.mymemory.translated.net/")
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(MyMemoryApi::class.java)

    suspend fun load(symbol: String): NewsSnapshot =
        withContext(Dispatchers.IO) {
            val normalized = normalizeSymbol(symbol)
            val query = buildSearchQuery(normalized)

            val originalItems = runCatching {
                fetchGoogleNews(query)
            }.getOrDefault(emptyList())

            if (originalItems.isEmpty()) {
                return@withContext NewsSnapshot(
                    score = 50,
                    confidence = 20,
                    items = emptyList()
                )
            }

            val translatedItems = coroutineScope {
                originalItems
                    .take(10)
                    .map { item ->
                        async {
                            val translatedTitle =
                                translateTitle(item.title)

                            item.copy(
                                title = translatedTitle.ifBlank {
                                    item.title
                                }
                            )
                        }
                    }
                    .awaitAll()
            }

            val score = calculateNewsScore(originalItems)
            val confidence = calculateConfidence(originalItems)

            NewsSnapshot(
                score = score,
                confidence = confidence,
                items = translatedItems
            )
        }

    private suspend fun fetchGoogleNews(
        query: String
    ): List<NewsItem> {
        val encodedQuery =
            URLEncoder.encode(
                query,
                StandardCharsets.UTF_8.toString()
            )

        val response =
            newsApi.search(encodedQuery).string()

        return parseRss(response)
            .filter { it.title.isNotBlank() }
            .take(10)
    }

    /**
     * ترجمه عنوان خبر فقط برای نمایش.
     *
     * تحلیل احساسات قبل از ترجمه و روی متن انگلیسی اصلی انجام می‌شود.
     */
    private suspend fun translateTitle(
        title: String
    ): String {
        if (title.isBlank()) return ""

        // سرویس اول
        val googleApiResult =
            runCatching {
                val response =
                    googleTranslateApi.translate(
                        client = "gtx",
                        sourceLanguage = "en",
                        targetLanguage = "fa",
                        format = "t",
                        text = title,
                        html = "1",
                        inputEncoding = "UTF-8",
                        outputEncoding = "UTF-8"
                    ).string()

                parseGoogleTranslation(response)
            }.getOrDefault("")

        if (googleApiResult.isNotBlank()) {
            return googleApiResult
        }

        // سرویس دوم
        val googleWebResult =
            runCatching {
                val response =
                    googleTranslateWebApi.translate(
                        client = "gtx",
                        sourceLanguage = "en",
                        targetLanguage = "fa",
                        format = "t",
                        text = title,
                        html = "1",
                        inputEncoding = "UTF-8",
                        outputEncoding = "UTF-8"
                    ).string()

                parseGoogleTranslation(response)
            }.getOrDefault("")

        if (googleWebResult.isNotBlank()) {
            return googleWebResult
        }

        // سرویس سوم
        val myMemoryResult =
            runCatching {
                val response =
                    myMemoryApi.translate(
                        query = title,
                        languagePair = "en|fa"
                    )

                parseMyMemoryTranslation(response)
            }.getOrDefault("")

        if (myMemoryResult.isNotBlank()) {
            return myMemoryResult
        }

        return ""
    }

    private fun parseGoogleTranslation(
        raw: String
    ): String {
        if (raw.isBlank()) return ""

        return runCatching {
            val root =
                org.json.JSONArray(raw)

            val sentences =
                root.optJSONArray(0)
                    ?: return@runCatching ""

            val result =
                StringBuilder()

            for (i in 0 until sentences.length()) {
                val sentence =
                    sentences.optJSONArray(i)
                        ?: continue

                val translated =
                    sentence.optString(0).trim()

                if (translated.isNotBlank()) {
                    if (result.isNotEmpty()) {
                        result.append(" ")
                    }

                    result.append(translated)
                }
            }

            cleanTranslatedText(
                result.toString()
            )
        }.getOrDefault("")
    }

    private fun parseMyMemoryTranslation(
        response: MyMemoryResponse?
    ): String {
        if (response == null) return ""

        return runCatching {
            val text =
                response.responseData
                    ?.translatedText
                    ?.trim()
                    .orEmpty()

            cleanTranslatedText(text)
        }.getOrDefault("")
    }

    private fun cleanTranslatedText(
        text: String
    ): String {
        if (text.isBlank()) return ""

        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("\\u0026", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseRss(
        xml: String
    ): List<NewsItem> {
        if (xml.isBlank()) return emptyList()

        val result =
            ArrayList<NewsItem>()

        return runCatching {
            val parser =
                Xml.newPullParser()

            parser.setInput(
                xml.reader()
            )

            var eventType =
                parser.eventType

            var insideItem = false

            var title = ""
            var link = ""
            var description = ""
            var source = ""

            while (
                eventType != XmlPullParser.END_DOCUMENT
            ) {
                when (eventType) {

                    XmlPullParser.START_TAG -> {
                        when (
                            parser.name.lowercase()
                        ) {

                            "item" -> {
                                insideItem = true
                                title = ""
                                link = ""
                                description = ""
                                source = ""
                            }

                            "title" -> {
                                if (insideItem) {
                                    title =
                                        parser.nextText()
                                            .trim()
                                }
                            }

                            "link" -> {
                                if (insideItem) {
                                    link =
                                        parser.nextText()
                                            .trim()
                                }
                            }

                            "description" -> {
                                if (insideItem) {
                                    description =
                                        parser.nextText()
                                            .trim()
                                }
                            }

                            "source" -> {
                                if (insideItem) {
                                    source =
                                        parser.nextText()
                                            .trim()
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (
                            parser.name.equals(
                                "item",
                                ignoreCase = true
                            )
                        ) {
                            if (title.isNotBlank()) {

                                val originalTitle =
                                    cleanRssTitle(title)

                                val cleanDescription =
                                    cleanRssDescription(
                                        description
                                    )

                                val sentiment =
                                    calculateSentiment(
                                        originalTitle,
                                        cleanDescription
                                    )

                                result += NewsItem(
                                    title = originalTitle,
                                    source = cleanSource(source),
                                    link = link,
                                    description = cleanDescription,
                                    sentiment = sentiment
                                )
                            }

                            insideItem = false
                        }
                    }
                }

                eventType =
                    parser.next()
            }

            result
        }.getOrDefault(emptyList())
    }

    private fun cleanRssTitle(
        title: String
    ): String {
        return title
            .replace(
                Regex("\\s+-\\s+[^-]+$"),
                ""
            )
            .replace(
                Regex("\\s+\\|\\s+[^|]+$"),
                ""
            )
            .replace(
                Regex("\\s+—\\s+[^—]+$"),
                ""
            )
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanRssDescription(
        description: String
    ): String {
        if (description.isBlank()) return ""

        return description
            .replace(
                Regex("<[^>]*>"),
                " "
            )
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

    private fun cleanSource(
        source: String
    ): String {
        return source
            .replace(
                Regex("<[^>]*>"),
                ""
            )
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
            .ifBlank {
                "نامشخص"
            }
    }

    /**
     * تحلیل احساسات روی عنوان و توضیح انگلیسی اصلی.
     */
    private fun calculateSentiment(
        title: String,
        description: String
    ): Int {
        val text =
            "$title $description"
                .lowercase()

        val positiveWords =
            listOf(
                "surge",
                "surges",
                "surging",
                "rally",
                "rallies",
                "rising",
                "rise",
                "rises",
                "gains",
                "gain",
                "bullish",
                "breakout",
                "breaks out",
                "record",
                "high",
                "higher",
                "growth",
                "positive",
                "approval",
                "approved",
                "adoption",
                "partnership",
                "launch",
                "success",
                "strong",
                "stronger",
                "recovery",
                "recover",
                "inflow",
                "inflows",
                "accumulate",
                "accumulation",
                "buying"
            )

        val negativeWords =
            listOf(
                "crash",
                "crashes",
                "fall",
                "falls",
                "falling",
                "drop",
                "drops",
                "dropping",
                "decline",
                "declines",
                "declining",
                "bearish",
                "breakdown",
                "breaks down",
                "low",
                "lower",
                "loss",
                "losses",
                "negative",
                "rejection",
                "rejected",
                "ban",
                "banned",
                "lawsuit",
                "hack",
                "hacked",
                "attack",
                "risk",
                "risks",
                "warning",
                "warnings",
                "outflow",
                "outflows",
                "sell",
                "selling",
                "liquidation",
                "liquidations",
                "fraud",
                "scam"
            )

        var positive = 0
        var negative = 0

        positiveWords.forEach { word ->
            if (text.contains(word)) {
                positive++
            }
        }

        negativeWords.forEach { word ->
            if (text.contains(word)) {
                negative++
            }
        }

        return when {
            positive == 0 &&
                negative == 0 -> 0

            positive > negative ->
                minOf(
                    100,
                    20 + (positive - negative) * 15
                )

            negative > positive ->
                maxOf(
                    -100,
                    -20 - (negative - positive) * 15
                )

            else -> 0
        }
    }

    private fun calculateNewsScore(
        items: List<NewsItem>
    ): Int {
        if (items.isEmpty()) return 50

        val average =
            items
                .take(10)
                .map { it.sentiment }
                .average()

        return when {
            average >= 60 -> 90
            average >= 35 -> 80
            average >= 15 -> 70
            average >= 5 -> 60
            average <= -60 -> 10
            average <= -35 -> 20
            average <= -15 -> 30
            average <= -5 -> 40
            else -> 50
        }
    }

    private fun calculateConfidence(
        items: List<NewsItem>
    ): Int {
        return when {
            items.size >= 8 -> 85
            items.size >= 5 -> 75
            items.size >= 3 -> 60
            items.size >= 1 -> 40
            else -> 20
        }
    }

    private fun buildSearchQuery(
        symbol: String
    ): String {
        val coin =
            symbol
                .removeSuffix("USDT")
                .removeSuffix("USD")
                .trim()

        return "$coin cryptocurrency crypto"
    }

    private fun normalizeSymbol(
        input: String
    ): String {
        return input
            .trim()
            .uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace(" ", "")
            .let { symbol ->
                when {
                    symbol.endsWith("USDT") ->
                        symbol

                    symbol.endsWith("USD") ->
                        symbol.removeSuffix("USD") +
                            "USDT"

                    else ->
                        symbol + "USDT"
                }
            }
    }
}

private interface GoogleNewsApi {

    @GET("rss/search")
    suspend fun search(
        @Query("q", encoded = true)
        query: String
    ): ResponseBody
}

private interface GoogleTranslateApi {

    @Headers(
        "User-Agent: Mozilla/5.0",
        "Accept: application/json,text/plain,*/*"
    )
    @GET("translate_a/single")
    suspend fun translate(
        @Query("client")
        client: String,

        @Query("sl")
        sourceLanguage: String,

        @Query("tl")
        targetLanguage: String,

        @Query("dt")
        format: String,

        @Query("q")
        text: String,

        @Query("html")
        html: String,

        @Query("ie")
        inputEncoding: String,

        @Query("oe")
        outputEncoding: String
    ): ResponseBody
}

private interface MyMemoryApi {

    @GET("get")
    suspend fun translate(
        @Query("q")
        query: String,

        @Query("langpair")
        languagePair: String
    ): MyMemoryResponse
}

private data class MyMemoryResponse(
    val responseData: MyMemoryTranslation?
)

private data class MyMemoryTranslation(
    val translatedText: String?
)
