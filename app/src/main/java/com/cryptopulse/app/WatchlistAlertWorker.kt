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

                val context =
                    applicationContext

                createNotificationChannel(
                    context
                )

                val watchlistRepository =
                    WatchlistRepository(
                        context
                    )

                val analysisRepository =
                    WatchlistAnalysisRepository(
                        context
                    )

                val items =
                    watchlistRepository.getItems()

                if (items.isEmpty()) {
                    return@withContext Result.success()
                }

                val priceRepository =
                    WatchlistPriceRepository()

                val liveRepository =
                    LiveRepository()

                val newsRepository =
                    NewsRepository()

                /*
                 * دریافت قیمت تمام ارزهای واچ‌لیست
                 */
                val prices =
                    priceRepository.getPrices(
                        items.map {
                            it.symbol
                        }
                    )

                if (prices.isEmpty()) {
                    return@withContext Result.retry()
                }

                val now =
                    System.currentTimeMillis()

                /*
                 * =====================================================
                 * بروزرسانی قیمت و بررسی افت ۵٪
                 * =====================================================
                 */
                items.forEach { item ->

                    val currentPrice =
                        prices[item.symbol]
                            ?: return@forEach

                    if (item.buyPrice <= 0.0) {
                        return@forEach
                    }

                    val dropPercent =
                        (
                            (
                                currentPrice -
                                    item.buyPrice
                            ) /
                                item.buyPrice
                        ) * 100.0

                    /*
                     * مثال:
                     *
                     * قیمت خرید = 100
                     * حد هشدار = 95
                     *
                     * اگر قیمت فعلی <= 95 باشد:
                     * هشدار باید فعال شود.
                     */
                    val alertLimit =
                        item.buyPrice * 0.95

                    val shouldAlert =
                        currentPrice <=
                            alertLimit

                    /*
                     * =================================================
                     * افت ۵٪
                     * =================================================
                     */
                    if (
                        shouldAlert &&
                        !item.alertTriggered
                    ) {

                        /*
                         * مهم:
                         *
                         * تابع Notification اکنون Boolean برمی‌گرداند.
                         *
                         * فقط اگر ارسال Notification امکان‌پذیر باشد
                         * alertTriggered را true می‌کنیم.
                         *
                         * اگر مجوز Notification وجود نداشته باشد،
                         * false برمی‌گردد و در اجرای بعدی دوباره تلاش می‌شود.
                         */
                        val notificationSent =
                            showDropNotification(
                                context =
                                    context,
                                symbol =
                                    item.symbol,
                                buyPrice =
                                    item.buyPrice,
                                currentPrice =
                                    currentPrice,
                                dropPercent =
                                    dropPercent
                            )

                        watchlistRepository.updateItem(
                            symbol =
                                item.symbol,

                            buyPrice =
                                item.buyPrice,

                            currentPrice =
                                currentPrice,

                            lastUpdated =
                                now,

                            alertTriggered =
                                if (
                                    notificationSent
                                ) {
                                    true
                                } else {
                                    false
                                }
                        )

                    } else {

                        /*
                         * وقتی قیمت دوباره بالاتر از ۵٪ افت برگشت،
                         * هشدار برای افت بعدی دوباره فعال می‌شود.
                         */
                        val resetAlert =
                            currentPrice >
                                alertLimit

                        watchlistRepository.updateItem(
                            symbol =
                                item.symbol,

                            buyPrice =
                                item.buyPrice,

                            currentPrice =
                                currentPrice,

                            lastUpdated =
                                now,

                            alertTriggered =
                                if (
                                    resetAlert
                                ) {
                                    false
                                } else {
                                    item.alertTriggered
                                }
                        )
                    }
                }

                /*
                 * =====================================================
                 * بروزرسانی تحلیل مستقل هر ارز
                 * =====================================================
                 */
                items.forEach { item ->

                    runCatching {

                        val snapshot =
                            liveRepository.loadForScan(
                                item.symbol
                            )

                        if (
                            snapshot.candles.size <
                            60
                        ) {
                            return@runCatching
                        }

                        val news =
                            newsRepository.load(
                                item.symbol
                            )

                        val flow =
                            MarketFlowData(
                                openInterest =
                                    snapshot.openInterest,

                                fundingRate =
                                    snapshot.fundingRate,

                                openInterestHistory =
                                    snapshot.openInterestHistory,

                                longShortHistory =
                                    snapshot.longShortHistory,

                                takerVolumeHistory =
                                    snapshot.takerVolumeHistory
                            )

                        val result =
                            AnalysisEngine.analyze(
                                candles =
                                    snapshot.candles,

                                flow =
                                    flow,

                                newsScore =
                                    news.score,

                                newsConfidence =
                                    news.confidence,

                                btcCandles =
                                    snapshot.btcCandles
                            )

                        analysisRepository.saveFromResult(
                            symbol =
                                item.symbol,

                            result =
                                result,

                            signal =
                                result.signal,

                            analyzedAt =
                                now
                        )

                    }.onFailure {
                        /*
                         * خطای یک ارز نباید تحلیل ارزهای دیگر
                         * را متوقف کند.
                         */
                    }
                }

                Result.success()

            } catch (_: Exception) {

                Result.retry()
            }
        }

    /*
     * =============================================================
     * Notification
     * =============================================================
     *
     * نتیجه:
     *
     * true  = Notification ارسال شد / سیستم اجازه ارسال داد
     * false = Notification ارسال نشد
     */
    private fun showDropNotification(
        context: Context,
        symbol: String,
        buyPrice: Double,
        currentPrice: Double,
        dropPercent: Double
    ): Boolean {

        /*
         * Android 13+
         *
         * اگر مجوز Notification وجود نداشته باشد،
         * نباید alertTriggered را true کنیم.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                return false
            }
        }

        val manager =
            NotificationManagerCompat.from(
                context
            )

        /*
         * بررسی می‌کنیم Notificationهای برنامه
         * توسط کاربر کاملاً خاموش نشده باشند.
         */
        if (
            !manager.areNotificationsEnabled()
        ) {

            return false
        }

        /*
         * در Android 8+ کانال باید فعال باشد.
         *
         * اگر کاربر کانال را روی NONE گذاشته باشد،
         * هشدار نباید به عنوان ارسال‌شده ثبت شود.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val notificationManager =
                context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            val channel =
                notificationManager.getNotificationChannel(
                    CHANNEL_ID
                )

            if (
                channel != null &&
                channel.importance ==
                NotificationManager.IMPORTANCE_NONE
            ) {

                return false
            }
        }

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
                                    formatPrice(
                                        buyPrice
                                    )
                                )

                                append("\n")

                                append(
                                    "قیمت فعلی: "
                                )

                                append(
                                    formatPrice(
                                        currentPrice
                                    )
                                )

                                append("\n")

                                append(
                                    "سود/زیان: "
                                )

                                append(
                                    formatPercent(
                                        dropPercent
                                    )
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
                .setAutoCancel(
                    true
                )
                .build()

        return try {

            manager.notify(
                symbol.hashCode(),
                notification
            )

            true

        } catch (_: Exception) {

            false
        }
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

        val existingChannel =
            manager.getNotificationChannel(
                CHANNEL_ID
            )

        /*
         * اگر کانال قبلاً ساخته شده باشد،
         * تنظیمات آن را دوباره تغییر نمی‌دهیم؛
         * Android اجازه تغییر importance کانال موجود
         * را از داخل برنامه نمی‌دهد.
         */
        if (existingChannel != null) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "هشدار واچ‌لیست",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "هشدار کاهش ۵ درصدی قیمت ارزهای واچ‌لیست"

                enableVibration(
                    true
                )
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
  
