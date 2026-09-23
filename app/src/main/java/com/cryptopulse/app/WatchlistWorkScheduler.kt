package com.cryptopulse.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WatchlistWorkScheduler {

private const val WORK_NAME =
    WatchlistAlertWorker.WORK_NAME

fun schedule(
    context: Context
) {

    val request =
        PeriodicWorkRequestBuilder<WatchlistAlertWorker>(
            6,
            TimeUnit.HOURS
        )
            .build()

    WorkManager
        .getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
}

fun cancel(
    context: Context
) {

    WorkManager
        .getInstance(context.applicationContext)
        .cancelUniqueWork(
            WORK_NAME
        )
}

}
