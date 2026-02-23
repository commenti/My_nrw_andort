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
 * Logic: Receives Task -> Health Check -> Reload (If Dead) -> Wait -> Injects JS -> Observes DOM.
 * UPDATE: Implemented User's "Smart Pre-Flight Check & Reload" & Audio Auto-Play Fixes.
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
                // 🚨 CRITICAL FIX: यह Audio Hack (Immortality) को बिना User Click के चलने देगा
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
                    
                    // पेज लोड होते ही इमोर्टेलिटी स्क्रिप्ट इंजेक्ट करो
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)

                    Log.i(TAG, "STATE: Engine Ready. Page Fully Loaded.")
                    
                    // 🚨 WAIT & EXECUTE LOGIC: अगर कोई टास्क पेंडिंग है (रीलोड के कारण), तो उसे चलाएं
                    currentTask?.let { 
                        Log.i(TAG, "STATE: Page refreshed successfully. Executing buffered task ${it.id} now.")
                        executeTask(it) 
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
    // 🚨 SMART CHECK & RELOAD LOGIC
    // ==========================================
    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming.")
            currentTask = task
            
            // ज़बरदस्ती WebView को जगाने की कोशिश
            webView.resumeTimers()

            if (isPageLoaded) {
                Log.i(TAG, "CHECK: Verifying if App/WebView is ALIVE and DOM is ready...")
                
                val healthCheckScript = "(function(){ return (document.querySelector('textarea') !== null || document.querySelector('[contenteditable=\"true\"]') !== null).toString(); })();"
                
                webView.evaluateJavascript(healthCheckScript) { result ->
                    // 🚨 FIX: JS से रिजल्ट "true" (स्ट्रिंग) या "\"true\"" (कोटेड स्ट्रिंग) आ सकता है
                    val isAlive = result != null && (result == "true" || result == "\"true\"")
                    
                    if (isAlive) {
                        Log.i(TAG, "STATUS: App is ALIVE ✅. Injecting message directly...")
                        executeTask(task)
                    } else {
                        Log.w(TAG, "STATUS: App is ASLEEP or DOM is STALE ❌. Triggering Reload and Wait protocol...")
                        isPageLoaded = false
                        webView.reload() 
                    }
                }
            } else {
                Log.w(TAG, "BUFFER: Page was already dead. Triggering fresh load...")
                isPageLoaded = false
                webView.reload()
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
            // इंजेक्शन सक्सेस होने के बाद हार्वेस्टर चालू करो
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
        // 3 सेकंड रुक कर रीलोड मारो
        webView.postDelayed({ webView.reload() }, 3000)
    }

    // बैकग्राउंड में जाते वक्त भी WebView को चलने दो
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

