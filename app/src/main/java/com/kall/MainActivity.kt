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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isPageLoaded = false
    private var currentTask: InteractionTask? = null

    companion object {
        private const val TAG = "Kall_Muscle"
        private const val TARGET_URL = "https://chat.qwen.ai/" 
        private const val MAX_DOM_POLL_ATTEMPTS = 15 
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

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
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

            // 🚨 Re-attached the missing Bridge Reference here
            addJavascriptInterface(NeuroBridge(WeakReference(this@MainActivity)), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    isPageLoaded = true
                    
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)
                    Log.i(TAG, "STATE: HTML Loaded. Waiting for SPA to render DOM...")
                    
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
                webView.postDelayed({ pollDomReadinessAndExecute(attempt + 1) }, 1000)
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

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
        currentTask = null
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

// ==========================================
// 🌉 JS TO ANDROID COMMUNICATION BRIDGE 
// (Strictly attached at the bottom of MainActivity.kt)
// ==========================================
class NeuroBridge(private val activityRef: WeakReference<MainActivity>) {
    
    @JavascriptInterface
    fun onChunkProgress(currentChunk: Int, totalChunks: Int) {
        Log.i("Kall_Muscle", "JS: Injecting chunk $currentChunk of $totalChunks...")
    }

    @JavascriptInterface
    fun onInjectionSuccess(message: String) {
        Log.i("Kall_Muscle", "JS: $message")
        activityRef.get()?.handleInjectionSuccess()
    }

    @JavascriptInterface
    fun onResponseHarvested(response: String) {
        Log.i("Kall_Muscle", "JS: Harvesting Complete.")
        activityRef.get()?.handleResponseHarvested(response)
    }

    @JavascriptInterface
    fun onError(errorMessage: String) {
        Log.e("Kall_Muscle", "JS ERROR: $errorMessage")
        activityRef.get()?.handleError(errorMessage)
    }
}

