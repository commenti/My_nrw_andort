package com.kall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * ARCHITECTURE CONTRACT: WorkerService.kt
 * Role: The Shield & The Watchman. 
 * Purpose: Prevents Android 14/15 Doze mode and listens to Supabase 24/7.
 * When a heavy payload (task) arrives, it wakes up the MainActivity (WebView).
 */
class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KallWorkerChannel"
    private val ALARM_CHANNEL_ID = "KallAlarmChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()

        AppLogger.log("🛡️ WORKER: Booting 24/7 Shield for WebView Architecture...")
        
        // 🚨 Python Backend से 'बड़ा ट्रक' (Task) सुनने वाला राडार चालू करो
        SupabaseManager.initializeNetworkListener { task ->
            AppLogger.log("☁️ CLOUD: Heavy Payload Task ${task.id} received! Waking up WebView...")
            TaskMemory.currentTask = task
            triggerFullScreenAlarm() // WebView को धक्के मार कर जगाओ
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Listening to Python 24/7 in stealth mode...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
        return START_STICKY 
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Kall::BackgroundWorkerLock"
        ).apply { 
            acquire(10 * 60 * 1000L) // CPU को सोने नहीं देगा
        }
    }

    private fun triggerFullScreenAlarm() {
        try {
            // MainActivity (WebView Engine) को चालू करने का इंटेंट
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Android 14/15+ के लिए फुल स्क्रीन अलार्म
            val alarmNotification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Incoming AI Task")
                .setContentText("Waking up WebView Engine to handle payload...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true) 
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(99, alarmNotification)
            
        } catch (e: Exception) {
            AppLogger.log("❌ FATAL ALARM HACK ERROR: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Worker सर्विस के लिए साइलेंट चैनल
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Kall Worker", NotificationManager.IMPORTANCE_LOW))
            // अलार्म के लिए हाई प्रायोरिटी चैनल
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL_ID, "Kall Alarm", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
