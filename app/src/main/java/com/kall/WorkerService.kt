package com.kall

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
// 1. THE SHIELD: Keeps App Alive (Original)
// ==========================================
class WorkerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KallWorkerChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neuro-Link Active")
            .setContentText("Worker is connected to the cloud in stealth mode.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
        return START_STICKY 
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Kall::BackgroundWorkerLock"
        ).apply {
            acquire(10 * 60 * 1000L /*10 minutes max*/) 
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
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

// ==========================================
// 2. THE INVISIBLE ROBOT: Accessibility Service
// ==========================================
class AutoBotService : AccessibilityService() {

    companion object {
        private const val TAG = "Kall_Robot"
    }

    private var isTyping = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = TaskMemory.currentTask ?: return
        val rootNode = rootInActiveWindow ?: return

        // 1. अगर टास्क पेंडिंग है तो टाइप करके सेंड करेगा
        if (task.status == "pending" && !isTyping) {
            executeInjection(rootNode, task)
        } 
        // 2. अगर सेंड हो गया है तो रिप्लाई का इंतज़ार करेगा
        else if (task.status == "processing") {
            harvestReply(rootNode, task)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "SYSTEM: Robot Interrupted!")
    }

    private fun executeInjection(rootNode: AccessibilityNodeInfo, task: InteractionTask) {
        isTyping = true
        // असली ऐप का 'Text Box' ढूंढो
        val inputBox = findNodeByClass(rootNode, "android.widget.EditText")
        
        if (inputBox != null) {
            Log.i(TAG, "ROBOT: Input box found. Injecting payload directly into memory...")
            
            // मैसेज टाइप करो
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.prompt)
            inputBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            // थोड़ी देर रुक कर Send बटन दबाएं
            CoroutineScope(Dispatchers.Main).launch {
                delay(800) // UI को अपडेट होने का समय दें
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    val sendBtn = findSendButton(freshRoot)
                    if (sendBtn != null) {
                        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "ROBOT: Payload sent! Waiting for AI reply...")
                        
                        // मेमोरी अपडेट करो ताकि बार-बार सेंड न करे
                        TaskMemory.currentTask = task.copy(status = "processing")
                    } else {
                        Log.e(TAG, "ROBOT ERROR: Send button not found!")
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
            
            // अगर रिप्लाई में थोड़ा भी टेक्स्ट आ गया और वह हमारे प्रॉम्प्ट से अलग है
            if (latestReply.length > 10 && latestReply != task.prompt) {
                Log.i(TAG, "ROBOT: Reply Harvested! Sending back to Cloud...")
                
                val completedTask = task.copy(status = "completed", response = latestReply)
                SupabaseManager.updateTaskAndAcknowledge(completedTask)
                
                // मिशन पास! मेमोरी क्लियर कर दो
                TaskMemory.currentTask = null
            }
        }
    }

    // --- ROBOT VISION (Helper Functions) ---

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
