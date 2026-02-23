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
        private const val MAX_WAIT_ATTEMPTS = 30 // 30 seconds wait for UI
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        requestBatteryExemption()
        startWorkerService()
        setupHeadlessWebView()
        setContentView(webView)
        
        // 🚨 नेटवर्क पोलिंग चालू की
        SupabaseManager.initializeNetworkListener(this, this::onNewTaskReceived)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Battery Exemption Error: ${e.message}")
            }
        }
    }

    private fun startWorkerService() {
        val intent = Intent(this, WorkerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(NeuroBridge(WeakReference(this@MainActivity)), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    view?.evaluateJavascript(JsInjector.BOOT_IMMORTALITY_SCRIPT, null)
                    
                    if (currentTask != null) {
                        checkUiAndExecute(0)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "WebView Error. Reloading...")
                    triggerSelfHealingProtocol(true)
                }
            }
        }
        webView.loadUrl(TARGET_URL)
    }

    fun onNewTaskReceived(task: InteractionTask) {
        runOnUiThread {
            currentTask = task 
            if (isPageLoaded) {
                checkUiAndExecute(0)
            }
        }
    }

    private fun checkUiAndExecute(attempt: Int) {
        if (attempt >= MAX_WAIT_ATTEMPTS) {
            triggerSelfHealingProtocol(keepTask = true)
            return
        }

        val checkScript = "(function(){ return (document.querySelector('textarea') !== null || document.querySelector('[contenteditable=\"true\"]') !== null).toString(); })();"
        
        webView.evaluateJavascript(checkScript) { result ->
            if (result != null && (result == "true" || result == "\"true\"")) {
                currentTask?.let { executeTask(it) }
            } else {
                webView.postDelayed({ checkUiAndExecute(attempt + 1) }, 1000)
            }
        }
    }

    private fun executeTask(task: InteractionTask) {
        val script = JsInjector.buildDispatchScript(task.prompt)
        webView.evaluateJavascript(script, null)
    }

    fun handleInjectionSuccess() {
        runOnUiThread { webView.evaluateJavascript(JsInjector.HARVESTER_SCRIPT, null) }
    }

    fun handleResponseHarvested(response: String) {
        runOnUiThread {
            currentTask?.let {
                SupabaseManager.updateTaskAndAcknowledge(it.copy(response = response, status = "completed"))
            }
            currentTask = null
        }
    }

    fun handleError(errorMessage: String) {
        runOnUiThread {
            Log.e(TAG, "Injection Error: $errorMessage")
            triggerSelfHealingProtocol(keepTask = true)
        }
    }

    private fun triggerSelfHealingProtocol(keepTask: Boolean) {
        isPageLoaded = false
        if (!keepTask) currentTask = null 
        webView.postDelayed({ webView.reload() }, 2000)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}

class NeuroBridge(private val activityRef: WeakReference<MainActivity>) {
    @JavascriptInterface fun onChunkProgress(currentChunk: Int, totalChunks: Int) {}
    @JavascriptInterface fun onInjectionSuccess(message: String) { activityRef.get()?.handleInjectionSuccess() }
    @JavascriptInterface fun onResponseHarvested(response: String) { activityRef.get()?.handleResponseHarvested(response) }
    @JavascriptInterface fun onError(errorMessage: String) { activityRef.get()?.handleError(errorMessage) }
}
