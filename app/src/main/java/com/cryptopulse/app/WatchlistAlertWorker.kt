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
                 * =====================================================
                 * دریافت قیمت فعلی
                 * =====================================================
                 */
                val prices =
                    priceRepository.getPrices(
                        items.map {
                            it.symbol
                        }
                    )

                /*
                 * اگر هیچ قیمت دریافت نشد،
                 * اجازه می‌دهیم WorkManager دوباره تلاش کند.
                 */
                if (prices.isEmpty()) {
                    return@withContext Result.retry()
                }

                val now =
                    System.currentTimeMillis()

                /*
                 * =====================================================
                 * قیمت + هشدار افت ۵٪
                 * =====================================================
                 *
                 * قانون:
                 *
                 * اگر:
                 *
                 * (قیمت فعلی - قیمت خرید) / قیمت خرید * 100
                 *
                 * <= -5%
                 *
                 * باشد، هشدار ارسال می‌شود.
                 *
                 * نکته مهم:
                 *
                 * alertTriggered دیگر مانع هشدار دوره بعد نیست.
                 *
                 * بنابراین اگر قیمت همچنان -22% باشد،
                 * در اجرای ۶ ساعت بعد نیز دوباره هشدار می‌آید.
                 */
                items.forEach { item ->

                    val currentPrice =
                        prices[item.symbol]
                            ?: return@forEach

                    if (
                        item.buyPrice <= 0.0 ||
                        currentPrice <= 0.0
                    ) {
                        return@forEach
                    }

                    val pnlPercent =
                        (
                            (
                                currentPrice -
                                    item.buyPrice
                            ) /
                                item.buyPrice
                        ) * 100.0

                    /*
                     * منفی ۵ درصد یا بیشتر:
                     *
                     * -5%
                     * -10%
                     * -22%
                     *
                     * همگی هشدار می‌دهند.
                     */
                    val shouldAlert =
                        pnlPercent <= -5.0

                    if (shouldAlert) {

                        showDropNotification(
                            context =
                                context,

                            symbol =
                                item.symbol,

                            buyPrice =
                                item.buyPrice,

                            currentPrice =
                                currentPrice,

                            pnlPercent =
                                pnlPercent
                        )
                    }

                    /*
                     * قیمت فعلی همیشه ذخیره می‌شود.
                     *
                     * alertTriggered را برای سازگاری با
                     * ساختار فعلی WatchlistRepository نگه می‌داریم،
                     * ولی دیگر از آن برای جلوگیری از Notification
                     * استفاده نمی‌کنیم.
                     */
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
                            shouldAlert
                    )
                }

                /*
                 * =====================================================
                 * بروزرسانی تحلیل مستقل هر ارز
                 * =====================================================
                 *
                 * هر ۶ ساعت:
                 *
                 * قیمت ← بروزرسانی
                 * تحلیل ← بروزرسانی
                 * اخبار ← بروزرسانی
                 * جریان پول ← بروزرسانی
                 * برنامه معامله ← بروزرسانی
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
                         * تحلیل فقط از موتور اصلی برنامه.
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
                         * خطای یک ارز نباید ارزهای دیگر
                         * را متوقف کند.
                         */
                    }
                }

                Result.success()

            } catch (_: Exception) {

                /*
                 * خطای کلی:
                 * WorkManager دوباره تلاش می‌کند.
                 */
                Result.retry()
            }
        }

    /*
     * =============================================================
     * ارسال Notification
     * =============================================================
     */
    private fun showDropNotification(
        context: Context,
        symbol: String,
        buyPrice: Double,
        currentPrice: Double,
        pnlPercent: Double
    ) {

        /*
         * Android 13+
         *
         * بدون این مجوز Notification نمایش داده نمی‌شود.
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

        /*
         * اگر Notificationهای برنامه خاموش باشند،
         * ارسال ممکن نیست.
         */
        if (
            !manager.areNotificationsEnabled()
        ) {
            return
        }

        /*
         * بررسی کانال Notification
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
                return
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
                    "🔴 هشدار افت واچ‌لیست: $symbol"
                )
                .setContentText(
                    "زیان فعلی: ${formatPercent(pnlPercent)}"
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            buildString {

                                append(
                                    "⚠️ افت قیمت از قیمت خرید به ۵٪ یا بیشتر رسیده است."
                                )

                                append("\n\n")

                                append(
                                    "ارز: $symbol"
                                )

                                append("\n")

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
                                        pnlPercent
                                    )
                                )

                                append("\n\n")

                                append(
                                    "بررسی بعدی طبق برنامه پس‌زمینه انجام می‌شود."
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
                .setVibrate(
                    longArrayOf(
                        0,
                        500,
                        300,
                        500
                    )
                )
                .build()

        try {

            /*
             * شناسه ثابت برای هر ارز:
             * هر اجرای جدید Notification همان ارز را
             * به‌روزرسانی می‌کند و چند اعلان انباشته
             * برای یک اجرای واحد ایجاد نمی‌کند.
             */
            manager.notify(
                symbol.hashCode(),
                notification
            )

        } catch (_: Exception) {
            /*
             * خطای Notification نباید باعث شکست
             * کل Worker شود.
             */
        }
    }

    /*
     * =============================================================
     * Notification Channel
     * =============================================================
     */
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
         * اگر قبلاً ساخته شده، همان کانال را نگه می‌داریم.
         */
        if (existingChannel != null) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "هشدار افت قیمت واچ‌لیست",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "هشدار زمانی که قیمت فعلی ۵٪ یا بیشتر از قیمت خرید پایین‌تر باشد"

                enableVibration(
                    true
                )
            }

        manager.createNotificationChannel(
            channel
        )
    }

    /*
     * =============================================================
     * Price formatting
     * =============================================================
     */
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

    /*
     * =============================================================
     * Percentage formatting
     * =============================================================
     */
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
  
