package com.cryptopulse.app

import kotlin.math.max
import kotlin.math.min

/**

Walk-forward optimizer. It selects weights on a validation slice that is later

than the training slice, then reports performance on the following holdout.

This is intentionally small/grid based so it can run on-device without ML deps.
*/
data class OptimizationReport(
val baseline:AnalysisWeights,
val optimized:AnalysisWeights,
val baselineScore:Double,
val optimizedScore:Double,
val holdoutHitRate:Double,
val holdoutReturn:Double,
val holdoutBrier:Double,
val holdoutDrawdown:Double,
val folds:Int,
val note:String,
val accepted:Boolean = false,
val holdoutImprovement:Double = 0.0
)


object WeightOptimizer {
private val candidates = listOf(
AnalysisWeights(.30,.22,.18,.08,.05,.10,.07),
AnalysisWeights(.26,.26,.18,.08,.04,.10,.08),
AnalysisWeights(.28,.24,.20,.07,.04,.09,.08),
AnalysisWeights(.24,.28,.18,.08,.04,.10,.08),
AnalysisWeights(.26,.22,.22,.08,.05,.09,.08),
AnalysisWeights(.25,.24,.20,.10,.05,.08,.08),
AnalysisWeights(.28,.20,.18,.10,.06,.10,.08),
AnalysisWeights(.24,.24,.22,.08,.06,.08,.08),
AnalysisWeights(.27,.23,.19,.09,.05,.09,.08)
)

fun optimize(candles:List<Candle>, horizons:List<Int> = listOf(3,7,14,30), baseline:AnalysisWeights = AnalysisWeights()):OptimizationReport {  
    if(candles.size < 500) return OptimizationReport(baseline,baseline,0.0,0.0,0.0,0.0,0.0,0.0,0,"داده کافی نیست؛ حداقل حدود ۵۰۰ کندل لازم است.",false,0.0)  
    val split=(candles.size*0.70).toInt().coerceAtLeast(300)  
    val validation=candles.subList(0,split)  
    val holdout=candles.subList(max(0,split-200),candles.size)  
    val best=candidates.maxByOrNull { objective(BacktestEngine.run(validation,horizons,4,it)) } ?: baseline  
    val baseScore=objective(BacktestEngine.run(validation,horizons,4,baseline))  
    val optScore=objective(BacktestEngine.run(validation,horizons,4,best))  
    val hold=BacktestEngine.run(holdout,horizons,4,best)  
    val baseHold=BacktestEngine.run(holdout,horizons,4,baseline)  
    val holdImprovement=objective(hold)-objective(baseHold)  
    val improvement=optScore-baseScore  
    val accepted=hold.evaluatedSignals >= 30 && holdImprovement >= 0.01 && hold.brierScore <= baseHold.brierScore + 0.015 && hold.maxDrawdown <= baseHold.maxDrawdown + 0.05  
    val note=if(improvement<0.01) "بهبود داخل نمونه ناچیز است؛ وزن‌های پیش‌فرض حفظ شوند." else if(accepted) "وزن‌های جدید روی Holdout هم بهتر بودند؛ قابل قبول برای ذخیره‌سازی." else "وزن‌های جدید روی Holdout برتری کافی نداشتند؛ وزن‌های قبلی حفظ شوند."  
    return OptimizationReport(baseline,best,baseScore,optScore,hold.hitRate,hold.avgReturn,hold.brierScore,hold.maxDrawdown,1,note,accepted,holdImprovement)  
}  

private fun objective(s:BacktestStats):Double {  
    // Reward predictive quality and net return; penalize Brier error and drawdown.  
    return s.hitRate*0.45 + s.avgReturn.coerceIn(-0.10,0.10)*2.0 - s.brierScore*0.35 - s.maxDrawdown*0.25  
}

}
