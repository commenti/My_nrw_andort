package com.kall

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ==========================================
// 1. THE SHIELD & THE BRAIN (24/7 Listener)
// ==========================================
class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KallWorkerChannel"
    private val ALARM_CHANNEL_ID = "KallAlarmChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()

        Log.d("Kall_Shield", "BOOT: Starting 24/7 Cloud Listener...")
        SupabaseManager.initializeNetworkListener { task ->
            Log.i("Kall_Shield", "SIGNAL: New Task ${task.id} incoming. Triggering Alarm Hack!")
            TaskMemory.currentTask = task
            triggerFullScreenAlarm()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Worker is listening to Python 24/7...")
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
        ).apply { acquire(10 * 60 * 1000L) }
    }

    // 🚨 THE HACK: Android 15 के बैकग्राउंड ब्लॉक को तोड़ने के लिए Full-Screen Intent
    private fun triggerFullScreenAlarm() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmNotification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Incoming AI Task")
                .setContentText("Waking up the system...")
                .setPriority(NotificationCompat.PRIORITY_MAX) // सबसे हाई प्रायोरिटी
                .setCategory(NotificationCompat.CATEGORY_ALARM) // Android को लगेगा अलार्म बज रहा है
                .setFullScreenIntent(pendingIntent, true) // स्क्रीन पर जबरदस्ती फेकेगा
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(99, alarmNotification)
            
            Log.i("Kall_Shield", "HACK: Full-Screen Intent Fired! MainActivity should pop up now.")
        } catch (e: Exception) {
            Log.e("Kall_Shield", "ALARM HACK ERROR: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Kall Worker", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(serviceChannel)
            
            // अलार्म के लिए हाई प्रायोरिटी चैनल
            val alarmChannel = NotificationChannel(ALARM_CHANNEL_ID, "Kall Alarm", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(alarmChannel)
        }
    }
}

// ==========================================
// 2. THE INVISIBLE ROBOT
// ==========================================
class AutoBotService : AccessibilityService() {

    companion object { private const val TAG = "Kall_Robot" }
    private var isTyping = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = TaskMemory.currentTask ?: return
        val rootNode = rootInActiveWindow ?: return

        val currentPackage = rootNode.packageName?.toString() ?: ""
        if (!currentPackage.contains("qwenlm")) return 

        if (task.status == "pending" && !isTyping) {
            executeInjection(rootNode, task)
        } else if (task.status == "processing") {
            harvestReply(rootNode, task)
        }
    }

    override fun onInterrupt() { Log.w(TAG, "SYSTEM: Robot Interrupted!") }

    private fun executeInjection(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        isTyping = true
        val inputBox = findNodeByClass(rootNode, "android.widget.EditText")
        
        if (inputBox != null) {
            Log.i(TAG, "ROBOT: Input box found in Qwen. Injecting...")
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.prompt)
            inputBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    val sendBtn = findSendButton(freshRoot)
                    if (sendBtn != null) {
                        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "ROBOT: Payload sent! Waiting for reply...")
                        TaskMemory.currentTask = task.copy(status = "processing")
                    }
                }
                isTyping = false
            }
        } else {
            isTyping = false
        }
    }

    private fun harvestReply(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        val allTexts = mutableListOf<String>()
        extractAllTexts(rootNode, allTexts)

        if (allTexts.isNotEmpty()) {
            val latestReply = allTexts.last()
            if (latestReply.length > 5 && latestReply != task.prompt) {
                Log.i(TAG, "ROBOT: Reply Harvested! Sending back to Cloud...")
                val completedTask = task.copy(status = "completed", response = latestReply)
                SupabaseManager.updateTaskAndAcknowledge(completedTask)
                TaskMemory.currentTask = null 
            }
        }
    }

    private fun findNodeByClass(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains(className, true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClass(child, className)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && (node.className?.toString()?.contains("Button") == true || node.className?.toString()?.contains("Image") == true)) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("send")) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun extractAllTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (node.className?.toString()?.contains("TextView") == true) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) list.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractAllTexts(child, list)
        }
    }
}

