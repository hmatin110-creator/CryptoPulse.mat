package com.cryptopulse.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

data class MarketScanResult(
    val top10All: List<ScanCandidate>,
    val top10Top100: List<ScanCandidate>,
    val analyzedCount: Int,
    val universeCount: Int
)

class MarketScanner {

    private val spot = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(BinanceScannerApi::class.java)

    private val market = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(BinanceSpotApi::class.java)

    private val live = LiveRepository()
    private val news = NewsRepository()

    suspend fun scan(
        universeLimit: Int = 1000,
        technicalLimit: Int = 250,
        enrichLimit: Int = 100
    ): List<ScanCandidate> {
        return scanDetailed(
            universeLimit = universeLimit,
            technicalLimit = technicalLimit,
            enrichLimit = enrichLimit
        ).top10All
    }

    suspend fun scanDetailed(
        universeLimit: Int = 1000,
        technicalLimit: Int = 250,
        enrichLimit: Int = 100
    ): MarketScanResult = withContext(Dispatchers.IO) {

        val tickerResponse = runCatching {
            spot.ticker24h()
        }.getOrElse {
            return@withContext emptyScanResult()
        }

        val rawTicker = runCatching {
            tickerResponse.string()
        }.getOrDefault("[]")

        val tickers = runCatching {
            JSONArray(rawTicker)
        }.getOrDefault(JSONArray())

        val stableCoins = setOf(
            "USDCUSDT",
            "BUSDUSDT",
            "TUSDUSDT",
            "FDUSDUSDT",
            "USDPUSDT",
            "DAIUSDT",
            "USDEUSDT",
            "USD1USDT"
        )

        val symbols = mutableListOf<Pair<String, Double>>()

        for (i in 0 until tickers.length()) {
            val obj = tickers.optJSONObject(i) ?: continue

            val symbol = normalizeSymbol(
                obj.optString("symbol")
            )

            if (symbol.isBlank()) continue
            if (!symbol.endsWith("USDT")) continue
            if (stableCoins.contains(symbol)) continue

            val quoteVolume =
                obj.optDouble("quoteVolume", 0.0)

            if (!quoteVolume.isFinite()) continue
            if (quoteVolume <= 0.0) continue

            symbols += symbol to quoteVolume
        }

        val universe = symbols
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(universeLimit)

        if (universe.isEmpty()) {
            return@withContext emptyScanResult()
        }

        val technicalLimiter = Semaphore(8)

        val technicalCandidates =
            coroutineScope {

                universe.map { (symbol, quoteVolume) ->

                    async(Dispatchers.IO) {

                        technicalLimiter.withPermit {

                            runCatching {

                                val candlesRaw =
                                    market.klines(
                                        symbol,
                                        "1d",
                                        180
                                    ).string()

                                val candles =
                                    parseKlines(candlesRaw)

                                if (candles.size < 60) {
                                    return@runCatching null
                                }

                                val preliminary =
                                    AnalysisEngine.analyze(
                                        candles = candles,
                                        flow = MarketFlowData(),
                                        newsScore = 50,
                                        newsConfidence = 0,
                                        btcCandles = null
                                    )

                                ScanCandidate(
                                    symbol = symbol,
                                    quoteVolume = quoteVolume,
                                    result = preliminary
                                )

                            }.getOrNull()
                        }
                    }
                }.awaitAll()
                    .filterNotNull()
            }

        if (technicalCandidates.isEmpty()) {
            return@withContext MarketScanResult(
                top10All = emptyList(),
                top10Top100 = emptyList(),
                analyzedCount = 0,
                universeCount = universe.size
            )
        }

        val rankedTechnical =
            technicalCandidates
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        rankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )

        val top100Symbols =
            universe
                .take(100)
                .map { it.first }
                .toSet()

        val technicalTop100 =
            rankedTechnical
                .filter {
                    top100Symbols.contains(it.symbol)
                }
                .take(100)

        val technicalTopOverall =
            rankedTechnical
                .take(technicalLimit)

        val enrichCandidates =
            (
                technicalTop100 +
                    technicalTopOverall
                )
                .distinctBy { it.symbol }
                .take(
                    maxOf(
                        enrichLimit,
                        technicalTop100.size
                    )
                )

        val enrichLimiter = Semaphore(6)

        val enrichedCandidates =
            coroutineScope {

                enrichCandidates.map { candidate ->

                    async(Dispatchers.IO) {

                        enrichLimiter.withPermit {

                            runCatching {

                                val snapshot =
                                    live.loadForScan(
                                        candidate.symbol
                                    )

                                val newsSnapshot =
                                    news.load(
                                        candidate.symbol
                                    )

                                val flow =
                                    MarketFlowData(
                                        openInterest =
                                            snapshot.openInterest,
                                        fundingRate =
                                            snapshot.fundingRate,
                                        openInterestHistory =
                                            snapshot.openInterestHistory,
                                        longShortHistory =
                                            snapshot.longShortHistory,
                                        takerVolumeHistory =
                                            snapshot.takerVolumeHistory
                                    )

                                val finalResult =
                                    AnalysisEngine.analyze(
                                        candles =
                                            snapshot.candles,
                                        flow = flow,
                                        newsScore =
                                            newsSnapshot.score,
                                        newsConfidence =
                                            newsSnapshot.confidence,
                                        btcCandles =
                                            snapshot.btcCandles
                                    )

                                candidate.copy(
                                    result = finalResult,
                                    newsScore =
                                        newsSnapshot.score,
                                    newsConfidence =
                                        newsSnapshot.confidence,
                                    flowLabel =
                                        finalResult
                                            .moneyFlowDetails
                                            .label
                                )

                            }.getOrElse {
                                candidate
                            }
                        }
                    }
                }.awaitAll()
            }

        val enrichedMap =
            enrichedCandidates.associateBy {
                it.symbol
            }

        val finalCandidates =
            technicalCandidates.map { candidate ->
                enrichedMap[candidate.symbol]
                    ?: candidate
            }

        val top10All =
            finalCandidates
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        finalRankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        val top10Top100 =
            finalCandidates
                .filter {
                    top100Symbols.contains(it.symbol)
                }
                .sortedWith(
                    compareByDescending<ScanCandidate> {
                        finalRankingScore(it)
                    }.thenByDescending {
                        it.quoteVolume
                    }
                )
                .take(10)

        MarketScanResult(
            top10All = top10All,
            top10Top100 = top10Top100,
            analyzedCount = finalCandidates.size,
            universeCount = universe.size
        )
    }

    private fun emptyScanResult(): MarketScanResult {
        return MarketScanResult(
            top10All = emptyList(),
            top10Top100 = emptyList(),
            analyzedCount = 0,
            universeCount = 0
        )
    }

    private fun rankingScore(
        candidate: ScanCandidate
    ): Double {

        val result = candidate.result

        val directional =
            maxOf(
                result.pump,
                result.dump
            )

        val confidence =
            result.confidence.coerceIn(0, 100)

        val scoreDistance =
            kotlin.math.abs(
                result.score - 50
            )

        val moneyFlowScore =
            result.moneyFlowDetails.score
                .coerceIn(0, 100)

        val flowStrength =
            kotlin.math.abs(
                moneyFlowScore - 50
            )

        return (
            scoreDistance * 0.40 +
                directional * 0.20 +
                confidence * 0.20 +
                flowStrength * 0.20
            )
    }

    private fun finalRankingScore(
        candidate: ScanCandidate
    ): Double {

        val result = candidate.result

        val directional =
            maxOf(
                result.pump,
                result.dump
            ).coerceIn(0, 100)

        val confidence =
            result.confidence.coerceIn(0, 100)

        val scoreStrength =
            kotlin.math.abs(
                result.score - 50
            ).coerceIn(0, 50) * 2.0

        val moneyFlowStrength =
            kotlin.math.abs(
                result.moneyFlow - 50
            ).coerceIn(0, 50) * 2.0

        val newsStrength =
            kotlin.math.abs(
                result.news - 50
            ).coerceIn(0, 50) * 2.0

        val structureStrength =
            kotlin.math.abs(
                result.structure.score - 50
            ).coerceIn(0, 50) * 2.0

        val divergenceStrength =
            kotlin.math.abs(
                result.divergence.score - 50
            ).coerceIn(0, 50) * 2.0

        val flowBonus =
            when {
                isHeavyInflow(
                    result.moneyFlowDetails.label
                ) -> 8.0

                isHeavyOutflow(
                    result.moneyFlowDetails.label
                ) -> 8.0

                else -> 0.0
            }

        val moneyConflictPenalty =
            when {
                result.score >= 65 &&
                    isHeavyOutflow(
                        result.moneyFlowDetails.label
                    ) -> 8.0

                result.score <= 35 &&
                    isHeavyInflow(
                        result.moneyFlowDetails.label
                    ) -> 8.0

                else -> 0.0
            }

        return (
            scoreStrength * 0.34 +
                directional * 0.16 +
                confidence * 0.18 +
                moneyFlowStrength * 0.12 +
                newsStrength * 0.07 +
                structureStrength * 0.05 +
                divergenceStrength * 0.04 +
                flowBonus -
                moneyConflictPenalty
            )
    }

    private fun isHeavyInflow(
        label: String
    ): Boolean {

        val normalized =
            label.trim().uppercase()

        return normalized.contains("HEAVY INFLOW") ||
            normalized.contains("INFLOW HEAVY") ||
            label.contains("ورود سنگین") ||
            label.contains("ورود غیرعادی")
    }

    private fun isHeavyOutflow(
        label: String
    ): Boolean {

        val normalized =
            label.trim().uppercase()

        return normalized.contains("HEAVY OUTFLOW") ||
            normalized.contains("OUTFLOW HEAVY") ||
            label.contains("خروج سنگین") ||
            label.contains("خروج غیرعادی")
    }

    private fun normalizeSymbol(
        raw: String
    ): String {

        var symbol =
            raw
                .trim()
                .uppercase()
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")

        if (symbol.isBlank()) {
            return ""
        }

        if (!symbol.endsWith("USDT")) {
            symbol += "USDT"
        }

        return symbol
    }

    private fun parseKlines(
        raw: String
    ): List<Candle> {

        val array =
            runCatching {
                JSONArray(raw)
            }.getOrElse {
                return emptyList()
            }

        val output =
            ArrayList<Candle>(
                array.length()
            )

        for (i in 0 until array.length()) {

            val row =
                array.optJSONArray(i)
                    ?: continue

            if (row.length() < 8) {
                continue
            }

            runCatching {

                val openTime =
                    row.optLong(
                        0,
                        0L
                    )

                val high =
                    row.optString(2)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val low =
                    row.optString(3)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val close =
                    row.optString(4)
                        .toDoubleOrNull()
                        ?: return@runCatching

                val volume =
                    row.optString(5)
                        .toDoubleOrNull()
                        ?: 0.0

                val quoteVolume =
                    row.optString(7)
                        .toDoubleOrNull()
                        ?: 0.0

                if (
                    close <= 0.0 ||
                    high <= 0.0 ||
                    low <= 0.0
                ) {
                    return@runCatching
                }

                output += Candle(
                    close = close,
                    high = high,
                    low = low,
                    volume = volume,
                    quoteVolume = quoteVolume,
                    openTime = openTime
                )
            }
        }

        return output
    }
}
