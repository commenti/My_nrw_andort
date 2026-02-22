package com.kall

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * ARCHITECTURE CONTRACT: MainActivity.kt
 * Role: The Listener & Wake-Up Trigger (No WebView).
 * Logic: Receives Task -> Wakes Screen Up -> Saves to Memory -> Launches Native App.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Kall_Muscle"
        // 🚨 यहाँ असली Qwen ऐप (या जो भी टारगेट ऐप है) का पैकेज नाम डाल देना
        const val TARGET_PACKAGE = "com.qwen.chat.ai" 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🚨 MAGIC: स्क्रीन ऑन रखने और बिना पासवर्ड वाला लॉक (Swipe) हटाने का हैक
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        requestBatteryExemption()
        startWorkerService()
        
        Log.d(TAG, "BOOT: Network Handshake Started (Native Mode)...")
        SupabaseManager.initializeNetworkListener(this::onNewTaskReceived)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "BATTERY ERROR: ${e.message}")
            }
        }
    }

    private fun startWorkerService() {
        val serviceIntent = Intent(this, WorkerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming. Waking up phone!")
            
            // 1. स्क्रीन की लाइट जलाओ (WakeLock)
            wakeUpScreen()

            // 2. टास्क को ग्लोबल मेमोरी में सेव करो (ताकि रोबोट पढ़ सके)
            TaskMemory.currentTask = task

            // 3. असली ऐप खोलो
            launchTargetApp()
        }
    }

    private fun wakeUpScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "Kall::AutoWakeupLock"
        )
        wakeLock.acquire(3000) // 3 सेकंड के लिए स्क्रीन ऑन करेगा
    }

    private fun launchTargetApp() {
        val intent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            Log.i(TAG, "LAUNCH: Opened target app -> $TARGET_PACKAGE")
        } else {
            Log.e(TAG, "ERROR: Target app not installed on this phone!")
        }
    }
}

// ==========================================
// 🚨 GLOBAL MEMORY (यहाँ टास्क सेव रहेगा)
// ==========================================
object TaskMemory {
    var currentTask: InteractionTask? = null
}

