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
        // 🚨 45 सेकंड तक UI लोड होने का इंतज़ार करेगा (Slow Internet के लिए)
        private const val MAX_DOM_POLL_ATTEMPTS = 45 
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

            addJavascriptInterface(NeuroBridge(WeakReference(this@MainActivity)), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    isPageLoaded = true
                    
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)
                    Log.i(TAG, "STATE: HTML Loaded. Waiting for SPA UI to render...")
                    
                    // 🚨 अगर कोई टास्क पेंडिंग है (रिफ्रेश के बाद), तो उसे दोबारा कंटिन्यू करो
                    if (currentTask != null) {
                        Log.i(TAG, "STATE: Resuming pending task ${currentTask?.id} after reload.")
                        pollDomReadinessAndExecute(0)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "WEBVIEW ERROR: ${error?.description}")
                    triggerSelfHealingProtocol(keepTask = true) // एरर पर रिफ्रेश करो, पर टास्क मत खोना
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming. Saving to memory.")
            currentTask = task // टास्क को सेव कर लिया
            webView.resumeTimers()

            if (isPageLoaded) {
                pollDomReadinessAndExecute(0)
            } else {
                Log.w(TAG, "BUFFER: Page is still loading. Will wait for UI.")
            }
        }
    }

    // 🚨 SMART WAIT LOGIC
    private fun pollDomReadinessAndExecute(attempt: Int) {
        if (attempt >= MAX_DOM_POLL_ATTEMPTS) {
            Log.w(TAG, "TIMEOUT: Slow Internet. UI not fully loaded after 45s. Refreshing App...")
            // टास्क को सेव रखते हुए पेज को फिर से लोड करो
            triggerSelfHealingProtocol(keepTask = true)
            return
        }

        // चेक करो कि क्या मैसेज लिखने वाला बॉक्स और बटन स्क्रीन पर सच में आ गए हैं
        val healthCheckScript = "(function(){ " +
                "const ta = document.querySelector('textarea'); " +
                "const ce = document.querySelector('[contenteditable=\"true\"]'); " +
                "return (ta !== null || ce !== null).toString(); })();"
        
        webView.evaluateJavascript(healthCheckScript) { result ->
            val isAlive = result != null && (result == "true" || result == "\"true\"")
            
            if (isAlive) {
                Log.i(TAG, "STATUS: UI is READY ✅. Injecting payload for task ${currentTask?.id}...")
                currentTask?.let { executeTask(it) }
            } else {
                Log.i(TAG, "STATUS: Waiting for UI to render... (Attempt ${attempt + 1}/$MAX_DOM_POLL_ATTEMPTS)")
                // 1 सेकंड रुको और फिर से चेक करो
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
            Log.i(TAG, "INJECTION: Success! Now waiting for AI response...")
            webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
        }
    }

    fun handleResponseHarvested(response: String) {
        runOnUiThread {
            currentTask?.let {
                val completedTask = it.copy(response = response, status = "completed")
                SupabaseManager.updateTaskAndAcknowledge(completedTask)
            }
            currentTask = null // टास्क पूरा हो गया, अब इसे मेमोरी से हटा दो
        }
    }

    fun handleError(errorMessage: String) {
        runOnUiThread {
            Log.e(TAG, "JS ERROR REPORTED: $errorMessage")
            // अगर JS फेल हुआ है, तो हो सकता है UI अटक गया हो। रिफ्रेश करो।
            triggerSelfHealingProtocol(keepTask = true)
        }
    }

    // 🚨 SMART REFRESH PROTOCOL
    fun triggerSelfHealingProtocol(keepTask: Boolean = false) {
        isPageLoaded = false
        if (!keepTask) {
            currentTask = null // सिर्फ तब डिलीट करो जब टास्क सच में फेल हो जाए
        }
        Log.i(TAG, "HEALING: Reloading WebView in 3 seconds...")
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
// ==========================================
class NeuroBridge(private val activityRef: WeakReference<MainActivity>) {
    
    @JavascriptInterface
    fun onChunkProgress(currentChunk: Int, totalChunks: Int) {}

    @JavascriptInterface
    fun onInjectionSuccess(message: String) {
        activityRef.get()?.handleInjectionSuccess()
    }

    @JavascriptInterface
    fun onResponseHarvested(response: String) {
        activityRef.get()?.handleResponseHarvested(response)
    }

    @JavascriptInterface
    fun onError(errorMessage: String) {
        activityRef.get()?.handleError(errorMessage)
    }
}

