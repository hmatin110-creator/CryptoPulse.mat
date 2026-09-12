package com.cryptopulse.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**

Safe on-device model update: learn -> evaluate on holdout -> accept only if

the new weights beat the stored weights by a margin and have enough signals.
*/
object AutoLearningStore {
private const val PREF = "cryptopulse_learning"
private const val KEY_WEIGHTS = "weights"
private const val KEY_LAST = "last_run"
private const val KEY_STATUS = "status"

fun getWeights(context: Context): AnalysisWeights {
val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
val raw = p.getString(KEY_WEIGHTS, null) ?: return AnalysisWeights()
val v = raw.split(',').mapNotNull { it.toDoubleOrNull() }
return if (v.size == 7) AnalysisWeights(v[0],v[1],v[2],v[3],v[4],v[5],v[6]).normalized() else AnalysisWeights()
}

fun saveWeights(context: Context, w: AnalysisWeights, status: String) {
context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
.putString(KEY_WEIGHTS, listOf(w.technical,w.moneyFlow,w.timeframe,w.structure,w.divergence,w.news,w.btc).joinToString(","))
.putLong(KEY_LAST, System.currentTimeMillis())
.putString(KEY_STATUS, status)
.apply()
}

fun lastRun(context: Context): Long = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
fun status(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_STATUS, "هنوز آموزش خودکار اجرا نشده") ?: "هنوز آموزش خودکار اجرا نشده"
}


class AutoLearningWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
override suspend fun doWork(): Result {
return runCatching {
val repo = LiveRepository()
val live = repo.load("BTCUSDT")
val current = AutoLearningStore.getWeights(applicationContext)
val report = WeightOptimizer.optimize(live.candles, listOf(3,7,14,30), current)
if (report.accepted) {
AutoLearningStore.saveWeights(applicationContext, report.optimized, "وزن‌های جدید پذیرفته شدند: بهبود Holdout = ${"%.4f".format(report.holdoutImprovement)}")
} else {
AutoLearningStore.saveWeights(applicationContext, current, "وزن‌های قبلی حفظ شدند؛ بهبود Holdout کافی نبود یا داده کم بود.")
}
Result.success()
}.getOrElse { Result.retry() }
}
}

fun scheduleAutoLearning(context: Context) {
val request = androidx.work.PeriodicWorkRequestBuilder<AutoLearningWorker>(7, TimeUnit.DAYS).build()
androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
"cryptopulse_auto_learning",
androidx.work.ExistingPeriodicWorkPolicy.KEEP,
request
)
}
