package com.kall

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. CREATE FULL-SCREEN CONSOLE WITH TEST BUTTON
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32, 32, 32, 32)
        }

        val testButton = Button(this).apply {
            text = "🚀 TEST ROBOT MANUALLY"
            setBackgroundColor(Color.parseColor("#4CAF50")) // Green Button
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener {
                AppLogger.log("🧪 MANUAL TEST: Waking up Robot!")
                TaskMemory.currentTask = InteractionTask("test-123", "Hello Qwen! Robot is working!", "pending")
                updateConsole()
                launchTargetApp()
            }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        logTextView = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 14f
            setTypeface(Typeface.MONOSPACE)
            text = AppLogger.getLogs()
        }

        scrollView.addView(logTextView)
        mainLayout.addView(testButton)
        mainLayout.addView(scrollView)
        setContentView(mainLayout)

        AppLogger.log("📱 UI: MainActivity Launched Successfully.")
        updateConsole()

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

        // 3. ANDROID 15 PERMISSION CHECKS
        checkAndroid15Permissions()
        startWorkerService()

        // 4. CHECK IF WOKEN UP BY CLOUD TASK
        if (TaskMemory.currentTask != null && TaskMemory.currentTask?.id != "test-123") {
            AppLogger.log("⚡ UI: Real Task detected from Cloud! Launching Qwen...")
            updateConsole()
            launchTargetApp()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        updateConsole()
        if (TaskMemory.currentTask != null) {
            AppLogger.log("⚡ UI: Woken up from background! Launching Qwen...")
            updateConsole()
            launchTargetApp()
        }
    }

    private fun updateConsole() {
        runOnUiThread { logTextView.text = AppLogger.getLogs() }
    }

    private fun checkAndroid15Permissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AppLogger.log("⚠️ Need Battery Exemption...")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AppLogger.log("❌ Alarm Intent Blocked by Android 15! Please Allow it.")
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppLogger.log("❌ Display Over Apps Blocked! Please Allow it.")
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
                AppLogger.log("✅ SUCCESS: Opened Qwen App!")
            } else {
                AppLogger.log("❌ ERROR: Qwen App NOT FOUND! Is it installed?")
            }
        } catch (e: Exception) {
            AppLogger.log("❌ LAUNCH ERROR: ${e.message}")
        }
        updateConsole()
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

object TaskMemory {
    var currentTask: InteractionTask? = null
}

object AppLogger {
    private val logs = mutableListOf<String>()
    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add("[$time] $message")
        android.util.Log.d("Kall_AppLogger", message)
    }
    fun getLogs(): String = logs.joinToString("\n\n")
}
