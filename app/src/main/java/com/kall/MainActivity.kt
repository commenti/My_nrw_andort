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
 * Logic: Receives Task -> Injects JS -> Observes DOM -> Returns Result.
 * UPDATE: Added Boot Immortality Script injection to prevent network sleep.
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

            // 🚨 SECURITY FIX: WeakReference पास कर रहे हैं ताकि Memory Leak न हो
            addJavascriptInterface(NeuroBridge(WeakReference(this@MainActivity)), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    isPageLoaded = true
                    
                    // 🚨 NEW HACK: पेज लोड होते ही ऐप को अमर और नेटवर्क को एक्टिव करने वाली स्क्रिप्ट चलाएं
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)

                    Log.i(TAG, "STATE: Engine Ready. Page Fully Loaded.")
                    currentTask?.let { executeTask(it) }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    triggerSelfHealingProtocol()
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            currentTask = task
            if (isPageLoaded) executeTask(task)
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

// 🚨 SECURITY FIX: Static Nested Class (No implicit reference to MainActivity)
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

