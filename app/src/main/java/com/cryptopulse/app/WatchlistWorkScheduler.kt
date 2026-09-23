package com.cryptopulse.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WatchlistWorkScheduler {

    private const val WORK_NAME =
        WatchlistAlertWorker.WORK_NAME

    private const val MANUAL_WORK_NAME =
        "Crypto110_Watchlist_Manual_Analysis"

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

    fun runNow(
        context: Context
    ) {

        val request =
            OneTimeWorkRequestBuilder<WatchlistAlertWorker>()
                .build()

        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(
                MANUAL_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
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

        WorkManager
            .getInstance(context.applicationContext)
            .cancelUniqueWork(
                MANUAL_WORK_NAME
            )
    }
}
