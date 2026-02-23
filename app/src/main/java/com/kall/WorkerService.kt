package com.kall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * ARCHITECTURE CONTRACT: WorkerService.kt
 * Role: The Shield & The Watchman.
 * Purpose: Single Source of Truth for DB Polling. Wakes MainActivity safely via Full-Screen Intents.
 * Compatibility: Hardened for Android 14 (API 34+) Foreground Service FGS policies.
 */
class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    
    companion object {
        private const val CHANNEL_ID = "KallWorkerChannel"
        private const val ALARM_CHANNEL_ID = "KallAlarmChannel"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 Minutes max per lock
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()

        AppLogger.log("🛡️ WORKER: Booting 24/7 Shield. Taking exclusive control of network polling.")
        
        // ARCHITECTURE RULE: Only WorkerService polls the network.
        // MainActivity must NOT call this method.
        SupabaseManager.initializeNetworkListener { task ->
            AppLogger.log("☁️ CLOUD: Heavy Payload Task ${task.id} received! Waking UI...")
            triggerFullScreenAlarm(task)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Maintaining autonomous background connection...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // FIXME: Replace with custom app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // 🚨 REQUIRED FOR ANDROID 14+: Must specify Foreground Service Type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, 
                1, 
                notification, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC 
                else 0
            )
        } else {
            startForeground(1, notification)
        }
        
        return START_STICKY 
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Kall::BackgroundWorkerLock"
            ).apply { 
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS) 
            }
        } catch (e: Exception) {
            AppLogger.log("⚠️ WAKELOCK ERROR: ${e.message}")
        }
    }

    private fun triggerFullScreenAlarm(task: InteractionTask) {
        try {
            // 🚨 FIX: Using SINGLE_TOP to preserve existing WebView state instead of CLEAR_TOP
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Pass task data securely via Intent Extras, NOT Global State
                putExtra("TASK_ID", task.id)
                putExtra("TASK_PROMPT", task.prompt)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 
                task.id.hashCode(), // Unique request code per task
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmNotification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Incoming AI Task")
                .setContentText("Processing payload: ${task.id}")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true) 
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(task.id.hashCode(), alarmNotification) // Use unique ID to prevent overwriting
            
        } catch (e: Exception) {
            AppLogger.log("❌ FATAL ALARM DISPATCH ERROR: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            AppLogger.log("⚠️ WAKELOCK RELEASE ERROR: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val workerChannel = NotificationChannel(CHANNEL_ID, "Background Worker", NotificationManager.IMPORTANCE_LOW)
            workerChannel.description = "Keeps the background service alive"
            manager.createNotificationChannel(workerChannel)
            
            val alarmChannel = NotificationChannel(ALARM_CHANNEL_ID, "Task Alarms", NotificationManager.IMPORTANCE_HIGH)
            alarmChannel.description = "Wakes the screen for incoming payloads"
            // Bypass Doze mode requirements
            alarmChannel.setBypassDnd(true)
            manager.createNotificationChannel(alarmChannel)
        }
    }
}

// ==========================================
// 🚨 SYSTEM OBSERVABILITY
// ==========================================
object AppLogger {
    private val logs = mutableListOf<String>()
    
    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formattedMsg = "[$time] $message"
        
        synchronized(logs) {
            if (logs.size > 500) logs.removeAt(0) // Prevent OutOfMemoryError
            logs.add(formattedMsg)
        }
        
        android.util.Log.i("Kall_AppLogger", message)
    }
    
    fun getLogs(): String {
        synchronized(logs) {
            return logs.joinToString("\n\n")
        }
    }
}
