package com.kall

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
 * UPDATE: Removed Desktop UserAgent for perfect Mobile Responsive UI.
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

        // Rule 1: CPU aur Screen ko sone nahi dena
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Rule 2: Start Background Service for Android 14/15
        startWorkerService()

        setupHeadlessWebView()
        
        // Rule 3: Zero XML. Direct view attachment.
        setContentView(webView)

        // Rule 4: Nervous System (Supabase) connection start
        Log.d(TAG, "BOOT: Initializing Network Handshake...")
        SupabaseManager.initializeNetworkListener(this::onNewTaskReceived)
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
                // 🚨 CRITICAL FIX: Desktop UserAgent has been removed.
                // Now Qwen will load in its native Mobile-Responsive layout.
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
        isPageLoaded = false
        webView.postDelayed({ webView.reload() }, 3000)
    }

    inner class NeuroBridge {

        @JavascriptInterface
        fun onInjectionSuccess(message: String) {
            Log.i(TAG, "JS: Input injected successfully.")
            runOnUiThread {
                webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
            }
        }

        @JavascriptInterface
        fun onResponseHarvested(response: String) {
            Log.i(TAG, "JS: Harvesting Complete.")
            runOnUiThread {
                currentTask?.let {
                    val completedTask = it.copy(response = response, status = "COMPLETED")
                    SupabaseManager.updateTaskAndAcknowledge(completedTask)
                    Log.i(TAG, "FINISH: Task ${it.id} processed.")
                }
                currentTask = null
            }
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            Log.e(TAG, "JS ERROR: $errorMessage")
            runOnUiThread {
                currentTask?.let {
                    val failedTask = it.copy(response = errorMessage, status = "FAILED")
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
