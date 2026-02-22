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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KallWorkerChannel"
    private val ALARM_CHANNEL_ID = "KallAlarmChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()

        AppLogger.log("🛡️ WORKER: Booting 24/7 Shield...")
        SupabaseManager.initializeNetworkListener { task ->
            AppLogger.log("☁️ CLOUD: Task ${task.id} received! Triggering Hack...")
            TaskMemory.currentTask = task
            triggerFullScreenAlarm()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Listening to Python 24/7...")
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
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Kall Worker", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL_ID, "Kall Alarm", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

// ==========================================
// 🚨 SMART ROBOT (अब डिब्बा छुप नहीं सकता)
// ==========================================
class AutoBotService : AccessibilityService() {
    private var isTyping = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = TaskMemory.currentTask ?: return
        val rootNode = rootInActiveWindow ?: return

        val currentPackage = rootNode.packageName?.toString() ?: ""
        if (!currentPackage.contains("qwenlm")) return 

        if (task.status == "pending" && !isTyping) {
            // हर बार स्क्रीन बदलने पर रोबोट डिब्बा ढूंढेगा (Retry Logic)
            executeInjection(rootNode, task)
        } else if (task.status == "processing") {
            harvestReply(rootNode, task)
        }
    }

    override fun onInterrupt() {}

    private fun executeInjection(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        // 🚨 MAGIC: अब हम EditText नहीं, "Editable" प्रॉपर्टी ढूंढ रहे हैं!
        val inputBox = findEditableNode(rootNode)
        
        if (inputBox != null) {
            isTyping = true // डिब्बा मिल गया, अब और मत ढूंढो
            AppLogger.log("🎯 ROBOT: Text Box Locked! Injecting message...")
            
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.prompt)
            inputBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            CoroutineScope(Dispatchers.Main).launch {
                delay(1000) // बटन दबाने से पहले 1 सेकंड रुको
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    val sendBtn = findSendButton(freshRoot)
                    if (sendBtn != null) {
                        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        AppLogger.log("🚀 ROBOT: Send button clicked! Waiting for reply...")
                        TaskMemory.currentTask = task.copy(status = "processing")
                    } else {
                        AppLogger.log("❌ ROBOT ERROR: Box found, but Send Button missing!")
                    }
                }
                isTyping = false // प्रोसेस खत्म
            }
        } else {
            // डिब्बा नहीं मिला (शायद स्क्रीन लोड हो रही है), रोबोट चुप रहेगा और अगली इवेंट का इंतज़ार करेगा
        }
    }

    private fun harvestReply(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        val allTexts = mutableListOf<String>()
        extractAllTexts(rootNode, allTexts)

        if (allTexts.isNotEmpty()) {
            val latestReply = allTexts.last()
            if (latestReply.length > 5 && latestReply != task.prompt) {
                AppLogger.log("🤖 ROBOT: Reply harvested! Sending to Cloud...")
                val completedTask = task.copy(status = "completed", response = latestReply)
                SupabaseManager.updateTaskAndAcknowledge(completedTask)
                TaskMemory.currentTask = null 
            }
        }
    }

    // 🚨 SMART NODE FINDER
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // अगर डिब्बे में टाइप हो सकता है, तो यही हमारा टारगेट है!
        if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        
        // Qwen का सेंड बटन ImageView, Button या View कुछ भी हो सकता है
        if (node.isClickable && (desc.contains("send") || text.contains("send") || desc.contains("submit") || desc.contains("भेजें"))) {
            return node
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

