package com.cryptopulse.app

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface BinanceSpotApi {
@GET("api/v3/klines")
suspend fun klines(@Query("symbol") symbol:String,@Query("interval") interval:String,@Query("limit") limit:Int=500): ResponseBody
}

interface BinanceFuturesApi {
@GET("fapi/v1/openInterest")
suspend fun openInterest(@Query("symbol") symbol:String): OpenInterestDto
@GET("fapi/v1/fundingRate")
suspend fun fundingRate(@Query("symbol") symbol:String,@Query("limit") limit:Int=10): List<FundingDto>
@GET("futures/data/openInterestHist")
suspend fun openInterestHistory(@Query("pair") pair:String,@Query("period") period:String="4h",@Query("limit") limit:Int=30): List<OpenInterestHistDto>
@GET("futures/data/globalLongShortAccountRatio")
suspend fun longShort(@Query("pair") pair:String,@Query("period") period:String="4h",@Query("limit") limit:Int=30): List<LongShortDto>
@GET("futures/data/takerBuySellVol")
suspend fun takerVolume(@Query("pair") pair:String,@Query("contractType") contractType:String="PERPETUAL",@Query("period") period:String="4h",@Query("limit") limit:Int=30): List<TakerVolumeDto>
}

data class OpenInterestDto(val symbol:String,val openInterest:String,val time:Long)
data class FundingDto(val symbol:String,val fundingTime:Long,val fundingRate:String)
data class OpenInterestHistDto(val pair:String,val contractType:String,val sumOpenInterest:String,val sumOpenInterestValue:String,val timestamp:Long)
data class LongShortDto(val pair:String,val longShortRatio:String,val longAccount:String,val shortAccount:String,val timestamp:Long)
data class TakerVolumeDto(val pair:String,val contractType:String,val takerBuyVol:String,val takerSellVol:String,val takerBuyVolValue:String,val takerSellVolValue:String,val timestamp:Long)

interface BinanceScannerApi {
@GET("api/v3/ticker/24hr")
suspend fun ticker24h(): ResponseBody
}

data class ScanCandidate(
val symbol:String,
val quoteVolume:Double,
val result:AnalysisResult,
val newsScore:Int = 50,
val newsConfidence:Int = 0,
val flowLabel:String = "NEUTRAL"
)
