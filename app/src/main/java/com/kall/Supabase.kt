package com.kall

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    private const val TABLE_QUEUE = "ai_tasks"

    private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"

    private const val TIMEOUT_MS = 10000
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun showLogAndToast(context: Context, message: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, message) else Log.i(TAG, message)
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun initializeNetworkListener(context: Context, onNewTask: (InteractionTask) -> Unit) {
        Log.i(TAG, "SYSTEM BOOT: Pure HTTP Polling Started...")
        
        networkScope.launch {
            while (true) {
                fetchPendingTask(context, onNewTask)
                delay(1500) // 1.5 सेकंड (बहुत तेज़ पोलिंग नेटवर्क को ब्लॉक कर सकती है)
            }
        }
    }

    private fun fetchPendingTask(context: Context, onNewTask: (InteractionTask) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?select=id,prompt&status=eq.pending&limit=1")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("apikey", SUPABASE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 200) {
                val responseStr = readStream(connection.inputStream)
                
                // 🚨 AI के कारण होने वाले JSON क्रैश को फिक्स किया
                if (responseStr.isNotBlank() && responseStr != "[]") {
                    val jsonArray = JSONArray(responseStr)
                    if (jsonArray.length() > 0) {
                        val firstObj = jsonArray.getJSONObject(0)
                        val id = firstObj.optString("id", "")
                        val prompt = firstObj.optString("prompt", "")

                        if (id.isNotEmpty() && prompt.isNotEmpty()) {
                            showLogAndToast(context, "Task Detected: $id")
                            val task = InteractionTask(id = id, prompt = prompt, status = "pending")
                            
                            if (lockTask(task.id)) {
                                onNewTask(task)
                            }
                        }
                    }
                }
            } else {
                Log.e(TAG, "HTTP Error during GET: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch Crash: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun lockTask(taskId: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?id=eq.$taskId&status=eq.pending")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("apikey", SUPABASE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            connection.doOutput = true

            val jsonBody = JSONObject().apply { put("status", "processing") }
            connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

            connection.responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Lock Crash: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    fun updateTaskAndAcknowledge(task: InteractionTask) {
        networkScope.launch {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?id=eq.${task.id}")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connection.setRequestProperty("apikey", SUPABASE_KEY)
                connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonBody = JSONObject().apply {
                    put("status", task.status)
                    task.response?.let { put("response", it) }
                }

                connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode in 200..299) {
                    Log.i(TAG, "SUCCESS: Task ${task.id} updated to ${task.status}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update Crash: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun readStream(inputStream: InputStream?): String {
        return try {
            inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

