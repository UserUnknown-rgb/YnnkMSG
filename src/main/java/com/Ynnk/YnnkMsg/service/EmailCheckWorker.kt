package com.Ynnk.YnnkMsg.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.collection.emptyLongSet
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.YnnkMsgApplication
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.email.EmailService
import com.Ynnk.YnnkMsg.ui.contacts.ContactsActivity
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

private const val TAG = "EmailCheckWorker"

class EmailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_MESSAGES = "ynnkmsg_messages"
        private const val NOTIFICATION_ID_MESSAGES = 1002

        fun scheduleBackgroundPolling(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true) // Не проверять почту, если заряд низкий (<15%)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "email_background_poll",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelBackgroundPolling(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("email_background_poll")
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Messages"
            val descriptionText = "Notifications for new messages"
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val soundUri =
                "android.resource://${applicationContext.packageName}/${R.raw.notify_sound}".toUri()

            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = android.app.NotificationChannel(CHANNEL_MESSAGES, name, importance).apply {
                description = descriptionText
                setSound(soundUri, audioAttributes)
                enableLights(true)
                lightColor = android.graphics.Color.GREEN
            }

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)

        if (EmailCheckService.isRunning) {
            Log.d(TAG, "Worker skipped: Service is active")
            return Result.success()
        }

        if (YnnkMsgApplication.isAppInForeground) {
            Log.d(TAG, "App is in foreground, skipping worker execution.")
            return Result.success()
        }

        return try {
            //db.appEventDao().logEvent("Worker: Checking for mail...")
            val emailService = EmailService(applicationContext)
            val fetchResult = emailService.fetchNewMessages()

            fetchResult.onSuccess { count ->
                if (count > 0) {
                    db.appEventDao().logEvent("Worker: Check finished. Found $count new.")
                    showNewMessageNotification(count)
                }
            }
            fetchResult.onFailure { e ->
                db.appEventDao().logEvent("Worker: Check failed: ${e.message}")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in worker: ${e.message}")
            db.appEventDao().logEvent("Worker: Fatal error: ${e.message}")
            Result.retry()
        }
    }

    private fun showNewMessageNotification(count: Int) {
        createNotificationChannel();

        val intent = Intent(applicationContext, ContactsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.new_messages_notification, count))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_MESSAGES, notification)
    }
}
