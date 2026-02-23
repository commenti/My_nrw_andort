package com.kall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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

// ==========================================
// 1. THE SHIELD & THE BRAIN
// ==========================================
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
// 🚨 MATHEMATICAL HACKER ROBOT (X/Y TAP)
// ==========================================
class AutoBotService : AccessibilityService() {
    private var isTyping = false
    private var searchJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        AppLogger.log("🟢 ROBOT: Hacker Mode Activated!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val task = TaskMemory.currentTask ?: return
            val rootNode = rootInActiveWindow ?: return

            val currentPackage = rootNode.packageName?.toString() ?: ""
            if (!currentPackage.contains("qwenlm")) return 

            if (task.status == "pending" && !isTyping && searchJob == null) {
                AppLogger.log("🤖 ROBOT: Starting Math/Coordinate Injection...")
                startHuntingForBox(task)
            } else if (task.status == "processing") {
                harvestReply(rootNode, task)
            }
        } catch (e: Exception) {
            AppLogger.log("❌ EVENT ERROR: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    private fun startHuntingForBox(task: InteractionTask) {
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                isTyping = true
                var attempts = 0
                var injected = false
                
                while (!injected && attempts < 10) {
                    val root = rootInActiveWindow
                    val inputBox = if (root != null) findEditableNode(root) else null

                    if (inputBox != null) {
                        AppLogger.log("🎯 ROBOT: Box Locked! Applying Clipboard Paste...")
                        
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("robot_prompt", task.prompt)
                        clipboard.setPrimaryClip(clip)

                        inputBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        inputBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(1000) 
                        
                        val rootAfterKeyboard = rootInActiveWindow
                        val freshBox = if (rootAfterKeyboard != null) findEditableNode(rootAfterKeyboard) else null

                        if (freshBox != null) {
                            freshBox.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            AppLogger.log("✅ ROBOT: Text Pasted! Preparing Hacker Tap...")
                            
                            delay(1500) // टेक्स्ट प्रोसेस होने दो
                            
                            // 🧮 MATHEMATICAL CALCULATION FOR SEND BUTTON
                            val rect = Rect()
                            freshBox.getBoundsInScreen(rect)
                            
                            val screenWidth = resources.displayMetrics.widthPixels
                            // Y: डिब्बे का बिल्कुल सेंटर
                            val targetY = rect.centerY().toFloat()
                            // X: स्क्रीन के दाएं कोने से 80 पिक्सल पीछे (यहीं सेंड बटन होता है)
                            val targetX = (screenWidth - 80).toFloat()

                            AppLogger.log("🧮 MATH: Calculated Send Coord -> X:$targetX, Y:$targetY")
                            
                            performTap(targetX, targetY) // सीधा स्क्रीन पर उंगली मारो!

                            AppLogger.log("🚀 ROBOT: Target Clicked! Waiting for reply...")
                            TaskMemory.currentTask = task.copy(status = "processing")
                            injected = true 
                        }
                    } else {
                        AppLogger.log("👀 ROBOT: Searching for box... (${attempts + 1}/10)")
                        delay(1000)
                        attempts++
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("❌ CRASH PREVENTED: ${e.message}")
            } finally {
                isTyping = false
                searchJob = null 
            }
        }
    }

    // 👆 THE RAW SCREEN TAPPER
    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                AppLogger.log("👆 HACKER TAP: Screen officially touched at X/Y!")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppLogger.log("❌ HACKER TAP FAILED!")
            }
        }, null)
    }

    private fun harvestReply(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        try {
            val allTexts = mutableListOf<String>()
            extractAllTexts(rootNode, allTexts)

            if (allTexts.isNotEmpty()) {
                // 🚨 FILTER: सिर्फ वो रिप्लाई उठाओ जो हमारे भेजे हुए मैसेज से अलग हों
                val validReplies = allTexts.filter { 
                    it.length > 5 && !it.contains(task.prompt, ignoreCase = true) 
                }
                
                if (validReplies.isNotEmpty()) {
                    val latestReply = validReplies.last()
                    AppLogger.log("🤖 ROBOT: Real Reply Harvested! Sending to Cloud...")
                    val completedTask = task.copy(status = "completed", response = latestReply)
                    SupabaseManager.updateTaskAndAcknowledge(completedTask)
                    TaskMemory.currentTask = null 
                }
            }
        } catch (e: Exception) {
            AppLogger.log("❌ HARVEST ERROR: ${e.message}")
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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

