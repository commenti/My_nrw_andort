package com.kall

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * ARCHITECTURE CONTRACT: MainActivity.kt
 * Role: The Executor (Headless WebView & State Machine).
 * Constraints: No XML Layouts. Single Responsibility: DOM Manipulation & JS Bridge.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isPageLoaded = false
    private var currentTask: InteractionTask? = null

    companion object {
        private const val TAG = "NeuroLink_Muscle"
        private const val TARGET_URL = "https://chat.qwen.ai/" // Configure as per target AI
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ARCHITECTURE RULE: Prevent OS Doze Mode & Screen Sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupHeadlessWebView()
        
        // ARCHITECTURE RULE: Zero XML. Programmatic view attachment.
        setContentView(webView)

        // Initialize connection to The Nervous System (Supabase)
        // Supabase.initializeNetworkListener(this::onNewTaskReceived)
    }

    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Anti-Bot Evasion: Present as Desktop Chrome to avoid mobile-restricted UIs
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }

            // Establish the Bridge
            addJavascriptInterface(NeuroBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    Log.i(TAG, "STATE UPDATE: Page Loaded. Ready for Execution.")
                    
                    // Unblock pending tasks if any were received during load
                    currentTask?.let { executeTask(it) }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e(TAG, "NETWORK ERROR: ${error?.description}")
                    triggerSelfHealingProtocol()
                }
            }
        }

        webView.loadUrl(TARGET_URL)
    }

    /**
     * Entry point for data from Supabase.kt
     */
    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "STATE UPDATE: Task ${task.id} Received.")
            currentTask = task
            if (isPageLoaded) {
                executeTask(task)
            } else {
                Log.w(TAG, "WARN: Page still loading. Task ${task.id} queued.")
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        Log.i(TAG, "ACTION: Dispatching Payload for Task ${task.id}.")
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

    private fun triggerSelfHealingProtocol() {
        Log.w(TAG, "ACTION: Triggering Self-Healing Protocol (Reload).")
        isPageLoaded = false
        webView.reload()
    }

    /**
     * @JavascriptInterface methods run on a background thread provided by the WebView.
     * All DOM or App State updates MUST be routed back to the Main Thread.
     */
    inner class NeuroBridge {

        @JavascriptInterface
        fun onInjectionSuccess(message: String) {
            Log.i(TAG, "BRIDGE: $message")
            runOnUiThread {
                // Dispatch successful, initialize observer to wait for completion
                webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
            }
        }

        @JavascriptInterface
        fun onResponseHarvested(response: String) {
            Log.i(TAG, "BRIDGE: Harvest Complete. Length: ${response.length}")
            runOnUiThread {
                val completedTask = currentTask?.copy(response = response, status = "COMPLETED")
                currentTask = null
                
                if (completedTask != null) {
                    // Handshake back to Supabase layer
                    // Supabase.updateTaskAndAcknowledge(completedTask)
                }
            }
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            Log.e(TAG, "BRIDGE ERROR: $errorMessage")
            runOnUiThread {
                val failedTask = currentTask?.copy(response = errorMessage, status = "FAILED")
                currentTask = null
                
                if (failedTask != null) {
                    // Handshake back to Supabase layer
                    // Supabase.updateTaskAndAcknowledge(failedTask)
                }
                
                triggerSelfHealingProtocol()
            }
        }
    }

    override fun onDestroy() {
        // Prevent memory leaks on component destruction
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
