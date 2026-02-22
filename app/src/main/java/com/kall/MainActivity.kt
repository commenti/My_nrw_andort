package com.kall

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. CREATE IN-APP CONSOLE (No XML needed)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        logTextView = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 14f
            setPadding(32, 32, 32, 32)
            text = AppLogger.getLogs()
        }
        scrollView.addView(logTextView)
        setContentView(scrollView)

        AppLogger.log("📱 UI: MainActivity Launched.")

        // 2. SMASH THE LOCK
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

        // 3. ANDROID 14/15 PERMISSION CHECKS (CRITICAL)
        checkAndroid15Permissions()
        startWorkerService()

        // 4. CHECK IF WOKEN UP BY CLOUD TASK
        if (TaskMemory.currentTask != null) {
            AppLogger.log("⚡ UI: Task detected! Launching Qwen...")
            launchTargetApp()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (TaskMemory.currentTask != null) {
            AppLogger.log("⚡ UI: Woken up from background! Launching Qwen...")
            launchTargetApp()
        }
    }

    private fun checkAndroid15Permissions() {
        // Battery Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AppLogger.log("⚠️ PERMISSION: Requesting Battery Exemption...")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
        
        // Android 14/15 Full Screen Intent (The Alarm Hack)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AppLogger.log("❌ PERMISSION: Android 14+ blocked Alarm Intent! Opening settings...")
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        // Display Over Other Apps (System Alert Window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppLogger.log("❌ PERMISSION: 'Display over other apps' is disabled! Opening settings...")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun launchTargetApp() {
        val targetPackage = "ai.qwenlm.chat.android"
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
                AppLogger.log("✅ SUCCESS: Opened target app: $targetPackage")
            } else {
                AppLogger.log("❌ ERROR: Target app not installed: $targetPackage")
            }
        } catch (e: Exception) {
            AppLogger.log("❌ FATAL LAUNCH ERROR: ${e.message}")
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
}

// ==========================================
// 🚨 GLOBAL MEMORY & SCREAMING LOGGER
// ==========================================
object TaskMemory {
    var currentTask: InteractionTask? = null
}

object AppLogger {
    private val logs = mutableListOf<String>()
    fun log(message: String) {
        logs.add(message)
        android.util.Log.d("Kall_AppLogger", message)
    }
    fun getLogs(): String = logs.joinToString("\n\n")
}
