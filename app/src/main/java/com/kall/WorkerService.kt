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
// 🚨 BULLETPROOF ROBOT (अब क्रैश नहीं होगा)
// ==========================================
class AutoBotService : AccessibilityService() {
    private var isTyping = false
    private var searchJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        AppLogger.log("🟢 ROBOT: Service Connected & 100% Alive!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val task = TaskMemory.currentTask ?: return
            val rootNode = rootInActiveWindow ?: return

            val currentPackage = rootNode.packageName?.toString() ?: ""
            if (!currentPackage.contains("qwenlm")) return 

            if (task.status == "pending" && !isTyping && searchJob == null) {
                AppLogger.log("🤖 ROBOT: Qwen App detected! Starting Sniper Mode...")
                startHuntingForBox(task)
            } else if (task.status == "processing") {
                harvestReply(rootNode, task)
            }
        } catch (e: Exception) {
            AppLogger.log("❌ EVENT CRASH PREVENTED: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    private fun startHuntingForBox(task: InteractionTask) {
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                isTyping = true
                var inputBox: AccessibilityNodeInfo? = null
                var attempts = 0
                
                while (inputBox == null && attempts < 10) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        inputBox = findEditableNode(root)
                    }
                    if (inputBox == null) {
                        AppLogger.log("👀 ROBOT: Searching for text box... (Attempt ${attempts + 1}/10)")
                        delay(1000)
                        attempts++
                    }
                }

                if (inputBox != null) {
                    AppLogger.log("🎯 ROBOT: Box Locked! Injecting message...")
                    
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.prompt)
                    inputBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                    delay(1500) // UI को अपडेट होने का पूरा समय दो
                    
                    val rootAfterType = rootInActiveWindow
                    if (rootAfterType != null) {
                        val sendBtn = findSendButton(rootAfterType)
                        if (sendBtn != null) {
                            sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            AppLogger.log("🚀 ROBOT: Send button clicked! Waiting for AI reply...")
                            TaskMemory.currentTask = task.copy(status = "processing")
                        } else {
                            AppLogger.log("❌ ROBOT ERROR: Box found, but could not identify Send Button!")
                        }
                    }
                } else {
                    AppLogger.log("❌ ROBOT ERROR: Could not find input box even after 10 seconds.")
                }
            } catch (e: Exception) {
                AppLogger.log("❌ HUNTER CRASH PREVENTED: ${e.message}")
            } finally {
                isTyping = false
                searchJob = null 
            }
        }
    }

    private fun harvestReply(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        try {
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
        } catch (e: Exception) {
            AppLogger.log("❌ HARVEST CRASH PREVENTED: ${e.message}")
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

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val possibleButtons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableButtons(rootNode, possibleButtons)

        AppLogger.log("🔍 ROBOT: Found ${possibleButtons.size} clickable elements on screen.")

        // 1. नाम से ढूँढने की कोशिश
        for (btn in possibleButtons) {
            val desc = btn.contentDescription?.toString()?.lowercase() ?: ""
            val text = btn.text?.toString()?.lowercase() ?: ""
            if (desc.contains("send") || desc.contains("submit") || desc.contains("arrow") || 
                desc.contains("up") || text.contains("send") || desc.contains("post") || desc.contains("भेजें")) {
                AppLogger.log("🎯 ROBOT: Send button found by name: $desc / $text")
                return btn
            }
        }

        // 2. 🚨 THE HACK: अगर कोई नाम नहीं मिला, तो सबसे आखिरी बटन
        if (possibleButtons.isNotEmpty()) {
            AppLogger.log("🎯 ROBOT: Send button found by Sniper Logic (Last element).")
            return possibleButtons.last()
        }

        return null
    }

    private fun collectClickableButtons(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        val isButtonOrImage = className.contains("Button") || className.contains("Image") || className.contains("ImageView")
        
        if (node.isClickable && isButtonOrImage) {
            list.add(node)
        } else if (!node.isClickable && isButtonOrImage && node.parent?.isClickable == true) {
            list.add(node.parent) 
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableButtons(child, list)
        }
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

