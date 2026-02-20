package com.kall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * ARCHITECTURE CONTRACT: WorkerService.kt
 * Role: The Shield. 
 * Purpose: Prevents Android 14/15 Doze mode by maintaining a Foreground Service 
 * and acquiring a Partial WakeLock.
 */
class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KallWorkerChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground Service Notification (अनिवार्य है Android 8+ के लिए)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Worker is connected to the cloud in stealth mode.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // Default icon
            .setPriority(NotificationCompat.PRIORITY_LOW) // साइलेंट रहेगा
            .build()

        // सर्विस को Foreground में प्रमोट करें
        startForeground(1, notification)

        // START_STICKY का मतलब है अगर सिस्टम मेमोरी कम होने पर इसे मार दे, 
        // तो रिसोर्स फ्री होते ही सिस्टम इसे दोबारा खुद चालू करेगा।
        return START_STICKY 
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need bound service for this architecture
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Kall::BackgroundWorkerLock"
        ).apply {
            acquire() // CPU को सोने नहीं देगा
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // मेमोरी लीक से बचने के लिए WakeLock रिलीज़ करें
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Kall Worker Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
