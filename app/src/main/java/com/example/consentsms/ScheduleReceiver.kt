package com.example.consentsms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        createChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("numbersText", intent.getStringExtra("numbersText") ?: "")
            putExtra("messageText", intent.getStringExtra("messageText") ?: "")
            putExtra("repeatCount", intent.getIntExtra("repeatCount", 1))
            putExtra("fromSchedule", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            intent.getIntExtra("requestCode", 0),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("زمان ارسال پیام رسید")
            .setContentText("برای باز کردن پیام و تأیید ارسال لمس کنید")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(intent.getIntExtra("requestCode", 0), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Consent SMS",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Scheduled SMS reminders"
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "consent_sms_schedule"
    }
}
