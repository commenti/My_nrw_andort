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
import java.lang.ref.WeakReference

/**
 * ARCHITECTURE CONTRACT: MainActivity.kt
 * Role: The Executor (Headless WebView & State Machine).
 * Logic: Implements DOM Readiness Polling for SPA compatibility.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isPageLoaded = false
    private var currentTask: InteractionTask? = null

    companion object {
        private const val TAG = "Kall_Muscle"
        private const val TARGET_URL = "https://chat.qwen.ai/" 
        private const val MAX_DOM_POLL_ATTEMPTS = 15 // 15 seconds max wait for SPA render
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        requestBatteryExemption()
        startWorkerService()
        setupHeadlessWebView()
        setContentView(webView)
        
        Log.d(TAG, "BOOT: Initializing Network Handshake...")
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false 
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    offscreenPreRaster = true 
                }
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(NeuroBridge(WeakReference(this@MainActivity)), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    isPageLoaded = true
                    
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)
                    Log.i(TAG, "STATE: HTML Loaded. Waiting for SPA to render DOM...")
                    
                    // 🚨 DO NOT EXECUTE IMMEDIATELY. Start DOM Polling.
                    if (currentTask != null) {
                        pollDomReadinessAndExecute(0)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "WEBVIEW ERROR: ${error?.description}")
                    triggerSelfHealingProtocol()
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    // ==========================================
    // 🚨 SMART DOM POLLER & EXECUTION LOGIC
    // ==========================================
    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming.")
            currentTask = task
            webView.resumeTimers()

            if (isPageLoaded) {
                pollDomReadinessAndExecute(0)
            } else {
                Log.w(TAG, "BUFFER: Page is dead. Triggering fresh load...")
                isPageLoaded = false
                webView.reload()
            }
        }
    }

    /**
     * Recursively polls the WebView until the React/Vue frontend actually renders the input box.
     */
    private fun pollDomReadinessAndExecute(attempt: Int) {
        if (attempt >= MAX_DOM_POLL_ATTEMPTS) {
            Log.e(TAG, "TIMEOUT: DOM never rendered the input box. Triggering Self-Healing...")
            triggerSelfHealingProtocol()
            return
        }

        val healthCheckScript = "(function(){ return (document.querySelector('textarea') !== null || document.querySelector('[contenteditable=\"true\"]') !== null).toString(); })();"
        
        webView.evaluateJavascript(healthCheckScript) { result ->
            val isAlive = result != null && (result == "true" || result == "\"true\"")
            
            if (isAlive) {
                Log.i(TAG, "STATUS: DOM is READY ✅. Injecting payload for task ${currentTask?.id}...")
                currentTask?.let { executeTask(it) }
            } else {
                Log.w(TAG, "STATUS: Waiting for DOM... (Attempt ${attempt + 1}/$MAX_DOM_POLL_ATTEMPTS)")
                // Wait 1 second and check again
                webView.postDelayed({ pollDomReadinessAndExecute(attempt + 1) }, 1000)
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

    // ==========================================
    // PUBLIC HANDLERS FOR THE BRIDGE
    // ==========================================
    
    fun handleInjectionSuccess() {
        runOnUiThread {
            webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
        }
    }

    fun handleResponseHarvested(response: String) {
        runOnUiThread {
            currentTask?.let {
                val completedTask = it.copy(response = response, status = "completed")
                SupabaseManager.updateTaskAndAcknowledge(completedTask)
            }
            currentTask = null
        }
    }

    fun handleError(errorMessage: String) {
        runOnUiThread {
            currentTask?.let {
                val failedTask = it.copy(response = errorMessage, status = "failed")
                SupabaseManager.updateTaskAndAcknowledge(failedTask)
            }
            currentTask = null
            triggerSelfHealingProtocol()
        }
    }

    fun triggerSelfHealingProtocol() {
        isPageLoaded = false
        currentTask = null // Prevent poison pill tasks from infinite loop reloading
        webView.postDelayed({ webView.reload() }, 3000)
    }

    override fun onPause() {
        super.onPause()
        webView.resumeTimers() 
    }

    override fun onStop() {
        super.onStop()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
