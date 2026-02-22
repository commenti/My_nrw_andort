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
 * UPDATE: Implemented User's "Smart Pre-Flight Check & Reload" Logic to beat Deep Sleep.
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

    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
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

                    Log.i(TAG, "STATE: Engine Ready. Page Fully Loaded.")
                    
                    // 🚨 WAIT & EXECUTE LOGIC: अगर कोई टास्क पेंडिंग है, तो लोड होने के बाद चलाएं
                    currentTask?.let { 
                        Log.i(TAG, "STATE: Page refreshed successfully. Executing buffered task ${it.id} now.")
                        executeTask(it) 
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    triggerSelfHealingProtocol()
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    // ==========================================
    // 🚨 USER'S BRILLIANT LOGIC: SMART CHECK & RELOAD
    // ==========================================
    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "SIGNAL: New Task ${task.id} incoming.")
            currentTask = task
            
            // ज़बरदस्ती WebView को जगाने की कोशिश
            webView.resumeTimers()

            if (isPageLoaded) {
                Log.i(TAG, "CHECK: Verifying if App/WebView is ALIVE and DOM is ready...")
                
                // यह JS कोड चेक करेगा कि वेबसाइट का चैट बॉक्स अभी मौजूद है या स्लीप हो गया है
                val healthCheckScript = "(function(){ return document.querySelector('textarea') !== null || document.querySelector('[contenteditable=\"true\"]') !== null; })();"
                
                webView.evaluateJavascript(healthCheckScript) { result ->
                    if (result == "true") {
                        Log.i(TAG, "STATUS: App is ALIVE ✅. Injecting message directly...")
                        executeTask(task)
                    } else {
                        Log.w(TAG, "STATUS: App is ASLEEP or STALE ❌. Triggering Reload and Wait protocol...")
                        // यूजर की डिमांड: पहले रीलोड करो, फिर वेट करो!
                        isPageLoaded = false
                        webView.reload() 
                        // नोट: reload होने के बाद onPageFinished खुद-ब-खुद पेंडिंग टास्क को इंजेक्ट कर देगा!
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

class NeuroBridge(private val activityRef: WeakReference<MainActivity>) {
    
    @JavascriptInterface
    fun onChunkProgress(currentChunk: Int, totalChunks: Int) {
        Log.i("Kall_Muscle", "JS: Injecting chunk $currentChunk of $totalChunks...")
    }

    @JavascriptInterface
    fun onInjectionSuccess(message: String) {
        Log.i("Kall_Muscle", "JS: Payload dispatched.")
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

