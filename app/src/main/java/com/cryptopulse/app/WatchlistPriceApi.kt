package com.cryptopulse.app

import retrofit2.http.GET
import retrofit2.http.Query

interface WatchlistPriceApi {

@GET("api/v3/ticker/price")
suspend fun tickerPrice(
    @Query("symbol") symbol: String
): WatchlistPriceDto

}

data class WatchlistPriceDto(
val symbol: String,
val price: String
)
