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

/**
 * Smart market scanner v0.8
 * Stage 1: rank a large USDT universe by 24h quote volume.
 * Stage 2: run cheap technical analysis on the top universe.
 * Stage 3: enrich only the strongest candidates with derivatives/money-flow + news.
 * This keeps API usage bounded while scanning 100+ symbols.
 */
class MarketScanner {
    private val spot = Retrofit.Builder().baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create()).build().create(BinanceScannerApi::class.java)
    private val market = Retrofit.Builder().baseUrl("https://api.binance.com/")
        .addConverterFactory(MoshiConverterFactory.create()).build().create(BinanceSpotApi::class.java)
    private val live = LiveRepository()
    private val news = NewsRepository()

    suspend fun scan(universeLimit: Int = 120, technicalLimit: Int = 35, enrichLimit: Int = 12): List<ScanCandidate> = withContext(Dispatchers.IO) {
        val tickers = JSONArray(spot.ticker24h().string())
        val stable = setOf("USDCUSDT","BUSDUSDT","TUSDUSDT","FDUSDUSDT","USDPUSDT","DAIUSDT")
        val symbols = mutableListOf<Pair<String,Double>>()
        for (i in 0 until tickers.length()) {
            val o = tickers.getJSONObject(i)
            val s = o.optString("symbol")
            if (s.endsWith("USDT") && !stable.contains(s)) {
                val q = o.optDouble("quoteVolume", 0.0)
                if (q > 0) symbols += s to q
            }
        }
        val universe = symbols.sortedByDescending { it.second }.take(universeLimit)

        // Stage 2: cheap technical pass over a much larger universe.
        val limiter = Semaphore(6)
        val technical = coroutineScope {
            universe.map { (symbol, qv) ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        runCatching {
                            val candles = parseKlines(market.klines(symbol, "1d", 180).string())
                            val result = AnalysisEngine.analyze(candles, MarketFlowData(), 50, 0, null)
                            ScanCandidate(symbol, qv, result)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }.sortedWith(compareByDescending<ScanCandidate> { rankingScore(it) }.thenByDescending { it.quoteVolume })
            .take(technicalLimit)

        // Stage 3: only the strongest candidates get expensive flow + news enrichment.
        val enriched = coroutineScope {
            technical.take(enrichLimit).map { candidate ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        runCatching {
                            val snapshot = live.loadForScan(candidate.symbol)
                            val ns = news.load(candidate.symbol)
                            val flow = MarketFlowData(
                                snapshot.openInterest,
                                snapshot.fundingRate,
                                snapshot.openInterestHistory,
                                snapshot.longShortHistory,
                                snapshot.takerVolumeHistory
                            )
                            val result = AnalysisEngine.analyze(snapshot.candles, flow, ns.score, ns.confidence, snapshot.btcCandles)
                            candidate.copy(result = result, newsScore = ns.score, newsConfidence = ns.confidence,
                                flowLabel = result.moneyFlowDetails.label)
                        }.getOrElse { candidate }
                    }
                }
            }.awaitAll()
        }

        (enriched + technical.drop(enrichLimit))
            .distinctBy { it.symbol }
            .sortedWith(compareByDescending<ScanCandidate> { rankingScore(it) }.thenByDescending { it.quoteVolume })
            .take(10)
    }

    private fun rankingScore(c: ScanCandidate): Double {
        val result = c.result
        val flowBonus = when (result.moneyFlowDetails.label) {
            "HEAVY INFLOW" -> 8.0
            "HEAVY OUTFLOW" -> 6.0
            else -> 0.0
        }
        val confidenceWeight = result.confidence / 100.0
        val directional = maxOf(result.pump, result.dump)
        return result.score * 0.55 + directional * 0.25 + result.confidence * 0.20 + flowBonus * confidenceWeight
    }

    private fun parseKlines(raw: String): List<Candle> {
        val a = JSONArray(raw)
        val out = ArrayList<Candle>(a.length())
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            out += Candle(r.getString(4).toDouble(), r.getString(2).toDouble(), r.getString(3).toDouble(), r.getString(5).toDouble())
        }
        return out
    }
}
