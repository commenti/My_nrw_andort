package com.kall // 🚨 FIX: यहाँ 'p' को छोटा कर दिया गया है!

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
 * UPDATE: Fixed Capital 'P' typo + Added Complete Immortality Protocol.
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

        // Rule 1: CPU और स्क्रीन को सोने नहीं देना
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 🚨 IMMORTALITY HACK: बैटरी ऑप्टिमाइज़ेशन से ऐप को बाहर निकालना
        requestBatteryExemption()

        // Rule 2: Android 14/15 के लिए Background Service स्टार्ट करना
        startWorkerService()

        setupHeadlessWebView()
        
        // Rule 3: Direct view attachment (बिना XML के)
        setContentView(webView)

        // Rule 4: Nervous System (Supabase) connection start
        Log.d(TAG, "BOOT: Initializing Network Handshake...")
        SupabaseManager.initializeNetworkListener(this::onNewTaskReceived)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    Log.w(TAG, "BATTERY: Requesting exemption to prevent deep sleep.")
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

    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                
                // 🚨 BACKGROUND HACK 1: WebView को स्क्रीन के बाहर भी रेंडर होने दें
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
        
        currentTask?.let {
            val failedTask = it.copy(response = "SYSTEM_ERROR: Mobile UI form submission caused page reload.", status = "failed")
            SupabaseManager.updateTaskAndAcknowledge(failedTask)
        }
        currentTask = null 
        
        isPageLoaded = false
        webView.postDelayed({ webView.reload() }, 3000)
    }

    // ==========================================
    // 🚨 BACKGROUND HACK 2: FORCE JS EXECUTION WHEN APP IS MINIMIZED
    // ==========================================
    override fun onPause() {
        super.onPause()
        // Android WebView को पॉज़ कर देता है, हम उसे ज़बरदस्ती वापस जगा रहे हैं
        webView.resumeTimers() 
        Log.i(TAG, "BACKGROUND: Forced WebView timers to stay awake during onPause.")
    }

    override fun onStop() {
        super.onStop()
        // ऐप पूरी तरह छुपने पर भी JS इंजन चालू रहेगा
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
                    Log.i(TAG, "FINISH: Task ${it.id} processed & sent back to Python.")
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

