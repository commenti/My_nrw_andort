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
 * Version: 2.1 (Production Ready - Integrated with SupabaseManager)
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

        // ARCHITECTURE RULE: Prevent OS Doze Mode & Screen Sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupHeadlessWebView()
        
        // ARCHITECTURE RULE: Zero XML. Programmatic view attachment.
        setContentView(webView)

        // PHASE 1: Initialize connection to The Nervous System (Supabase)
        // Passing '::onNewTaskReceived' as a functional reference
        SupabaseManager.initializeNetworkListener(this::onNewTaskReceived)
    }

    private fun setupHeadlessWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Anti-Bot Evasion: Present as Desktop Chrome
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }

            // Establish the Bridge for JS <-> Kotlin communication
            addJavascriptInterface(NeuroBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    Log.i(TAG, "STATE: Page Loaded. Worker Ready.")
                    
                    // Process task if it arrived during page load
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
     * Entry point for incoming data from SupabaseManager.
     * Guaranteed to run on Main Thread for WebView safety.
     */
    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            Log.i(TAG, "STATE: Task ${task.id} Received.")
            currentTask = task
            if (isPageLoaded) {
                executeTask(task)
            } else {
                Log.w(TAG, "WAIT: Page loading. Task ${task.id} buffered.")
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        Log.i(TAG, "ACTION: Dispatching Task ${task.id} to DOM.")
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

    private fun triggerSelfHealingProtocol() {
        Log.w(TAG, "ACTION: Self-Healing Triggered (WebView Reload).")
        isPageLoaded = false
        webView.reload()
    }

    /**
     * NEURO-BRIDGE: The link between AI Web UI and Android Logic.
     * Methods run on a Background Thread (WebView Thread).
     */
    inner class NeuroBridge {

        @JavascriptInterface
        fun onInjectionSuccess(message: String) {
            Log.i(TAG, "BRIDGE: Payload Injected Successfully.")
            runOnUiThread {
                // Initialize MutationObserver to harvest streaming response
                webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null)
            }
        }

        @JavascriptInterface
        fun onResponseHarvested(response: String) {
            Log.i(TAG, "BRIDGE: Data Harvested.")
            runOnUiThread {
                currentTask?.let {
                    val completedTask = it.copy(response = response, status = "COMPLETED")
                    
                    // PHASE 2: Handshake back to Supabase layer
                    SupabaseManager.updateTaskAndAcknowledge(completedTask)
                    
                    Log.i(TAG, "STATE: Task ${it.id} Completed successfully.")
                }
                currentTask = null
            }
        }

        @JavascriptInterface
        fun onError(errorMessage: String) {
            Log.e(TAG, "BRIDGE ERROR: $errorMessage")
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
        // Clean up resources to prevent memory leaks
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
