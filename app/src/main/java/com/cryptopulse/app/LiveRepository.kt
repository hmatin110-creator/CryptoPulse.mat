package com.cryptopulse.app

import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveRepository {
private val spot = Retrofit.Builder().baseUrl("https://api.binance.com/").addConverterFactory(MoshiConverterFactory.create()).build().create(BinanceSpotApi::class.java)
private val futures = Retrofit.Builder().baseUrl("https://fapi.binance.com/").addConverterFactory(MoshiConverterFactory.create()).build().create(BinanceFuturesApi::class.java)

suspend fun loadForScan(symbol:String): LiveSnapshot = withContext(Dispatchers.IO) {  
    val rows = parseKlines(spot.klines(symbol, "1d", 180).string())  
    val btcRows = if(symbol == "BTCUSDT") rows else parseKlines(spot.klines("BTCUSDT", "1d", 180).string())  
    val oi = runCatching { futures.openInterest(symbol).openInterest.toDouble() }.getOrNull()  
    val funding = runCatching { futures.fundingRate(symbol, 5).lastOrNull()?.fundingRate?.toDouble() }.getOrNull()  
    val pair = symbol.removeSuffix("USDT") + "USDT"  
    val oiHist = runCatching { futures.openInterestHistory(pair, "4h", 15) }.getOrDefault(emptyList())  
    val ls = runCatching { futures.longShort(pair, "4h", 15) }.getOrDefault(emptyList())  
    val taker = runCatching { futures.takerVolume(pair, "PERPETUAL", "4h", 15) }.getOrDefault(emptyList())  
    LiveSnapshot(rows, oi, funding, oiHist, ls, taker, btcRows)  
}  

suspend fun load(symbol:String): LiveSnapshot = withContext(Dispatchers.IO) {  
    val rows = parseKlines(spot.klines(symbol, "1d", 800).string())  
    val btcRows = if(symbol == "BTCUSDT") rows else parseKlines(spot.klines("BTCUSDT", "1d", 800).string())  
    val oi = runCatching { futures.openInterest(symbol).openInterest.toDouble() }.getOrNull()  
    val funding = runCatching { futures.fundingRate(symbol, 10).lastOrNull()?.fundingRate?.toDouble() }.getOrNull()  
    val pair = symbol.removeSuffix("USDT") + "USDT"  
    val oiHist = runCatching { futures.openInterestHistory(pair, "4h", 30) }.getOrDefault(emptyList())  
    val ls = runCatching { futures.longShort(pair, "4h", 30) }.getOrDefault(emptyList())  
    val taker = runCatching { futures.takerVolume(pair, "PERPETUAL", "4h", 30) }.getOrDefault(emptyList())  
    LiveSnapshot(rows, oi, funding, oiHist, ls, taker, btcRows)  
}  

private fun parseKlines(raw:String):List<Candle> {  
    val a=JSONArray(raw); val out=ArrayList<Candle>(a.length())  
    for(i in 0 until a.length()) { val r=a.getJSONArray(i); out += Candle(r.getString(4).toDouble(),r.getString(2).toDouble(),r.getString(3).toDouble(),r.getString(5).toDouble()) }  
    return out  
}

}

data class LiveSnapshot(
val candles:List<Candle>,
val openInterest:Double?,
val fundingRate:Double?,
val openInterestHistory:List<OpenInterestHistDto> = emptyList(),
val longShortHistory:List<LongShortDto> = emptyList(),
val takerVolumeHistory:List<TakerVolumeDto> = emptyList(),
val btcCandles:List<Candle> = emptyList()
)
