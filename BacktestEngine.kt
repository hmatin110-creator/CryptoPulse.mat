package com.cryptopulse.app

import kotlin.math.*

data class HorizonStats(
    val horizon:Int,
    val samples:Int,
    val signals:Int,
    val buys:Int,
    val sells:Int,
    val hitRate:Double,
    val avgReturn:Double,
    val maxDrawdown:Double,
    val brierScore:Double
)

data class BacktestStats(
    val samples:Int,
    val buySignals:Int,
    val sellSignals:Int,
    val evaluatedSignals:Int,
    val hitRate:Double,
    val avgReturn:Double,
    val maxDrawdown:Double,
    val brierScore:Double,
    val feeRate:Double,
    val slippageRate:Double,
    val horizons:List<HorizonStats> = emptyList(),
    val note:String
)

object BacktestEngine {
    // Conservative defaults: 0.10% fee + 0.05% slippage per side.
    private const val FEE = 0.0010
    private const val SLIPPAGE = 0.0005

    fun run(candles:List<Candle>, horizons:List<Int> = listOf(3,7,14,30), step:Int = 3, weights:AnalysisWeights=AnalysisWeights()):BacktestStats {
        val results = horizons.map { runHorizon(candles, it, step, weights) }
        val best = results.maxByOrNull { it.signals } ?: emptyStats(0)
        return BacktestStats(
            samples = results.sumOf { it.samples },
            buySignals = results.sumOf { it.buys },
            sellSignals = results.sumOf { it.sells },
            evaluatedSignals = results.sumOf { it.signals },
            hitRate = if(results.isEmpty()) 0.0 else results.map { it.hitRate }.average(),
            avgReturn = if(results.isEmpty()) 0.0 else results.map { it.avgReturn }.average(),
            maxDrawdown = results.maxOfOrNull { it.maxDrawdown } ?: 0.0,
            brierScore = if(results.isEmpty()) 0.0 else results.map { it.brierScore }.average(),
            feeRate = FEE,
            slippageRate = SLIPPAGE,
            horizons = results,
            note = if(best.signals < 10) "نمونه سیگنال کم است؛ نتیجه را قابل اتکا تلقی نکن." else
                "کارمزد و اسلیپیج لحاظ شده‌اند. Funding تاریخی هنوز داخل این بک‌تست نیست؛ بنابراین نتیجه محافظه‌کارانه اما کامل نیست."
        )
    }

    private fun runHorizon(candles:List<Candle>, horizon:Int, step:Int, weights:AnalysisWeights):HorizonStats {
        if(candles.size < 220 + horizon) return emptyStats(horizon)
        val returns = mutableListOf<Double>()
        val probs = mutableListOf<Double>()
        val targets = mutableListOf<Double>()
        var buys=0; var sells=0; var correct=0
        var equity=1.0; var peak=1.0; var maxDd=0.0; var samples=0
        var i=200
        while(i+horizon<candles.size){
            val train=candles.subList(0,i)
            val r=AnalysisEngine.analyze(train, newsScore=50, newsConfidence=0, btcCandles=train, weights=weights)
            samples++
            val rawRet=candles[i+horizon].close/candles[i].close-1.0
            val roundTripCost=2.0*(FEE+SLIPPAGE)
            when(r.signal){
                "BUY" -> {
                    buys++
                    val net=rawRet-roundTripCost
                    returns+=net
                    val target=if(net>0)1.0 else 0.0
                    probs+=r.pump/100.0; targets+=target
                    if(target==1.0) correct++
                    equity*=1.0+net.coerceIn(-0.30,0.30)
                }
                "SELL" -> {
                    sells++
                    val net=(-rawRet)-roundTripCost
                    returns+=net
                    val target=if(net>0)1.0 else 0.0
                    probs+=r.dump/100.0; targets+=target
                    if(target==1.0) correct++
                    equity*=1.0+net.coerceIn(-0.30,0.30)
                }
            }
            peak=max(peak,equity); maxDd=max(maxDd,1.0-equity/peak)
            i+=step
        }
        val signals=returns.size
        val hit=if(signals==0)0.0 else correct.toDouble()/signals
        val avg=if(signals==0)0.0 else returns.average()
        val brier=if(probs.isEmpty())0.0 else probs.indices.map{(probs[it]-targets[it]).pow(2)}.average()
        return HorizonStats(horizon,samples,signals,buys,sells,hit,avg,maxDd,brier)
    }

    private fun emptyStats(h:Int)=HorizonStats(h,0,0,0,0,0.0,0.0,0.0,0.0)
}
