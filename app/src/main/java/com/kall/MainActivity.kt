package com.kall

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * ARCHITECTURE CONTRACT: MainActivity.kt
 * Role: The Executor (Headless WebView & State Machine).
 * Logic: Receives Task -> Injects JS -> Observes DOM -> Returns Result.
 * STATUS: Production Ready. Syntax Verified.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isPageLoaded = false
    private var currentTask: InteractionTask? = null

    companion object {
        private const val TAG = "Kall_Muscle"
        private const val TARGET_URL = "https://chat.qwen.ai/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rule 1: Prevent CPU and Screen from sleeping
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Rule 2: Request Ignore Battery Optimizations (Doze Mode Bypass)
        requestBatteryExemption()

        // Rule 3: Start Foreground Service for persistence
        startWorkerService()

        // Rule 4: Initialize WebView configuration
        setupHeadlessWebView()

        // Rule 5: Direct view attachment (No XML)
        setContentView(webView)

        // Rule 6: Initialize Nervous System (Supabase)
        Log.d(TAG, "BOOT: Initializing Network Handshake...")
        SupabaseManager.initializeNetworkListener(this::onNewTaskReceived)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            // FIX: Removed the invalid `val packageName = packageName` line.
            // We can just use the inherited `packageName` property directly below.
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "BATTERY: Requesting exemption from Doze mode.")
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Log.i(TAG, "BATTERY: App is already whitelisted from optimizations.")
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

    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                // 🚨 BACKGROUND HACK 1: Allow offscreen rendering
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    offscreenPreRaster = true
                }
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(NeuroBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    isPageLoaded = true
                    Log.i(TAG, "STATE: Engine Ready. Page Fully Loaded.")

                    // Execute buffered task if exists
                    currentTask?.let {
                        Log.i(TAG, "STATE: Executing buffered task ${it.id}")
                        executeTask(it)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e(TAG, "NETWORK ERROR: ${error?.description}. Attempting reload.")
                    triggerSelfHealingProtocol()
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming.")
            currentTask = task
            if (isPageLoaded) {
                executeTask(task)
            } else {
                Log.w(TAG, "BUFFER: Page load pending. Task ${task.id} queued.")
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        Log.i(TAG, "ACTION: Dispatching Payload for Task ${task.id}")
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

    private fun triggerSelfHealingProtocol() {
        Log.w(TAG, "HEAL: WebView unstable. Reloading in 3s...")

        // Update current task to failed before wiping state
        currentTask?.let {
            val failedTask = it.copy(
                response = "SYSTEM_ERROR: Web resource failed or crash detected.",
                status = "failed"
            )
            SupabaseManager.updateTaskAndAcknowledge(failedTask)
        }
        currentTask = null

        isPageLoaded = false
        // Delay reload to allow network stack to clear
        webView.postDelayed({ webView.reload() }, 3000)
    }

    // ==========================================
    // 🚨 BACKGROUND HACK 2: FORCE JS EXECUTION WHEN APP IS MINIMIZED
    // ==========================================
    override fun onPause() {
        super.onPause()
        // Force WebView to process JavaScript even when activity is paused
        webView.resumeTimers()
        Log.i(TAG, "BACKGROUND: Forced WebView timers to stay awake during onPause.")
    }

    override fun onStop() {
        super.onStop()
        // Force WebView to process JavaScript even when activity is stopped
        webView.resumeTimers()
        Log.i(TAG, "BACKGROUND: Forced WebView timers to stay awake during onStop.")
    }

    // ==========================================
    // THE BRIDGE: Android <---> JavaScript
    // ==========================================
    inner class NeuroBridge {

        @JavascriptInterface
        fun onChunkProgress(currentChunk: Int, totalChunks: Int) {
            Log.i(TAG, "JS: Injecting chunk $currentChunk of $totalChunks...")
        }

        @JavascriptInterface
        fun onInjectionSuccess(message: String) {
            Log.i(TAG, "JS: Payload fully injected & dispatched. Starting Harvester...")
            runOnUiThread {
                webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
            }
        }

        @JavascriptInterface
        fun onResponseHarvested(response: String) {
            Log.i(TAG, "JS: Harvesting Complete.")
            runOnUiThread {
                currentTask?.let {
                    val completedTask = it.copy(response = response, status = "completed")
                    SupabaseManager.updateTaskAndAcknowledge(completedTask)
                    Log.i(TAG, "FINISH: Task ${it.id} processed & sent back to Server.")
                }
                currentTask = null
            }
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            Log.e(TAG, "JS ERROR: $errorMessage")
            runOnUiThread {
                currentTask?.let {
                    val failedTask = it.copy(response = errorMessage, status = "failed")
                    SupabaseManager.updateTaskAndAcknowledge(failedTask)
                }
                currentTask = null
                triggerSelfHealingProtocol()
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
