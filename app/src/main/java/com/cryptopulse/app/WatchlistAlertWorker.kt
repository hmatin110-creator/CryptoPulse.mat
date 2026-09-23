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

                /*
                 * اگر واچ‌لیست خالی باشد،
                 * اجرای Worker موفق محسوب می‌شود.
                 */
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
                 * دریافت قیمت تمام ارزهای واچ‌لیست.
                 */
                val prices =
                    priceRepository.getPrices(
                        items.map {
                            it.symbol
                        }
                    )

                /*
                 * اگر هیچ قیمتی دریافت نشد،
                 * Worker را ناموفق در نظر می‌گیریم
                 * تا WorkManager دوباره تلاش کند.
                 */
                if (prices.isEmpty()) {
                    return@withContext Result.retry()
                }

                val now =
                    System.currentTimeMillis()

                /*
                 * =====================================================
                 * بخش اول:
                 * بروزرسانی قیمت و بررسی افت ۵ درصدی
                 * =====================================================
                 */
                items.forEach { item ->

                    val currentPrice =
                        prices[item.symbol]
                            ?: return@forEach

                    val dropPercent =
                        (
                            (
                                currentPrice -
                                    item.buyPrice
                            ) /
                                item.buyPrice
                        ) * 100.0

                    /*
                     * هشدار زمانی فعال می‌شود که قیمت
                     * حداقل ۵٪ پایین‌تر از قیمت خرید باشد.
                     */
                    val alertLimit =
                        item.buyPrice * 0.95

                    val shouldAlert =
                        currentPrice <=
                            alertLimit

                    /*
                     * فقط یک بار برای همان افت هشدار می‌دهیم.
                     *
                     * وقتی قیمت دوباره بالاتر از محدوده ۵٪
                     * برگردد، alertTriggered ریست می‌شود
                     * تا در افت بعدی دوباره هشدار بدهد.
                     */
                    if (
                        shouldAlert &&
                        !item.alertTriggered
                    ) {

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
                                true
                        )

                    } else {

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
                 * بخش دوم:
                 * بروزرسانی تحلیل مستقل هر ارز
                 * =====================================================
                 */
                items.forEach { item ->

                    runCatching {

                        val snapshot =
                            liveRepository.loadForScan(
                                item.symbol
                            )

                        /*
                         * برای تحلیل حداقل ۶۰ کندل لازم است.
                         */
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

                        /*
                         * منطق اصلی تحلیل فقط از
                         * AnalysisEngine استفاده می‌کند.
                         */
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

                        /*
                         * فقط تحلیل همان ارز ذخیره می‌شود.
                         * تحلیل ارزهای دیگر دست‌نخورده می‌ماند.
                         */
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
                         * خطای یک ارز نباید باعث شود
                         * تحلیل سایر ارزهای واچ‌لیست متوقف شود.
                         */
                    }
                }

                Result.success()

            } catch (_: Exception) {

                /*
                 * خطای کلی:
                 * اجازه می‌دهیم WorkManager دوباره تلاش کند.
                 */
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

        /*
         * Android 13+ برای Notification
         * نیاز به POST_NOTIFICATIONS دارد.
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
                return
            }
        }

        val manager =
            NotificationManagerCompat.from(
                context
            )

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
