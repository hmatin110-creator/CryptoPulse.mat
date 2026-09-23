package com.cryptopulse.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class WatchlistAlertWorker(
appContext: Context,
workerParams: WorkerParameters
) : CoroutineWorker(
appContext,
workerParams
) {

override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {

        try {

            val context = applicationContext

            createNotificationChannel(context)

            val repository =
                WatchlistRepository(context)

            val items =
                repository.getItems()

            if (items.isEmpty()) {
                return@withContext Result.success()
            }

            val priceRepository =
                WatchlistPriceRepository()

            val prices =
                priceRepository.getPrices(
                    items.map { it.symbol }
                )

            val now =
                System.currentTimeMillis()

            items.forEach { item ->

                val currentPrice =
                    prices[item.symbol]
                        ?: return@forEach

                val dropPercent =
                    (
                        (currentPrice - item.buyPrice) /
                            item.buyPrice
                        ) * 100.0

                val alertLimit =
                    item.buyPrice * 0.95

                val shouldAlert =
                    currentPrice <= alertLimit

                if (shouldAlert &&
                    !item.alertTriggered
                ) {

                    showDropNotification(
                        context = context,
                        symbol = item.symbol,
                        buyPrice = item.buyPrice,
                        currentPrice = currentPrice,
                        dropPercent = dropPercent
                    )

                    repository.updateItem(
                        symbol = item.symbol,
                        buyPrice = item.buyPrice,
                        currentPrice = currentPrice,
                        lastUpdated = now,
                        alertTriggered = true
                    )

                } else {

                    /*
                     * وقتی قیمت دوباره بالاتر از
                     * محدوده ۵٪ افت رفت، هشدار بعدی
                     * برای افت جدید دوباره فعال می‌شود.
                     */
                    val resetAlert =
                        currentPrice > alertLimit

                    repository.updateItem(
                        symbol = item.symbol,
                        buyPrice = item.buyPrice,
                        currentPrice = currentPrice,
                        lastUpdated = now,
                        alertTriggered =
                            if (resetAlert) {
                                false
                            } else {
                                item.alertTriggered
                            }
                    )
                }
            }

            Result.success()

        } catch (_: Exception) {

            Result.retry()
        }
    }

private fun showDropNotification(
    context: Context,
    symbol: String,
    buyPrice: Double,
    currentPrice: Double,
    dropPercent: Double
) {

    if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.TIRAMISU
    ) {

        if (
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
    }

    val manager =
        NotificationManagerCompat.from(context)

    val notification =
        NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(
                "🔴 هشدار واچ‌لیست: $symbol"
            )
            .setContentText(
                "قیمت بیش از ۵٪ کاهش یافته است."
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            append(
                                "قیمت خرید: "
                            )
                            append(
                                formatPrice(buyPrice)
                            )
                            append("\n")

                            append(
                                "قیمت فعلی: "
                            )
                            append(
                                formatPrice(currentPrice)
                            )
                            append("\n")

                            append(
                                "سود/زیان: "
                            )
                            append(
                                formatPercent(dropPercent)
                            )
                        }
                    )
            )
            .setPriority(
                NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                NotificationCompat.CATEGORY_ALARM
            )
            .setAutoCancel(true)
            .build()

    manager.notify(
        symbol.hashCode(),
        notification
    )
}

private fun createNotificationChannel(
    context: Context
) {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.O
    ) {
        return
    }

    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    val channel =
        NotificationChannel(
            CHANNEL_ID,
            "هشدار واچ‌لیست",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {

            description =
                "هشدار کاهش ۵ درصدی قیمت ارزهای واچ‌لیست"

            enableVibration(true)
        }

    manager.createNotificationChannel(
        channel
    )
}

private fun formatPrice(
    value: Double
): String {

    return when {

        value >= 1000.0 ->
            String.format(
                Locale.US,
                "%.2f",
                value
            )

        value >= 1.0 ->
            String.format(
                Locale.US,
                "%.4f",
                value
            )

        else ->
            String.format(
                Locale.US,
                "%.8f",
                value
            )
    }
}

private fun formatPercent(
    value: Double
): String {

    return String.format(
        Locale.US,
        "%.2f%%",
        value
    )
}

companion object {

    const val WORK_NAME =
        "Crypto110_Watchlist_Alert"

    private const val CHANNEL_ID =
        "watchlist_price_alerts"
}

}
