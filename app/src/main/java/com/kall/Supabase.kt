package com.kall

import android.util.Log
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

/**
 * ARCHITECTURE CONTRACT: SupabaseManager
 * Role: 100% Native REST Polling (No WebSockets).
 * NOTE: InteractionTask is now strictly imported from api.kt
 */
object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    private const val TABLE_QUEUE = "ai_tasks"

    private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"

    private const val TIMEOUT_MS = 10000

    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initializeNetworkListener(onNewTask: (InteractionTask) -> Unit) {
        Log.i(TAG, "SYSTEM BOOT: Initializing Hardened REST Polling...")

        networkScope.launch {
            while (true) {
                fetchPendingTask(onNewTask)
                delay(2500)
            }
        }
    }

    private fun fetchPendingTask(onNewTask: (InteractionTask) -> Unit) {
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
                val jsonArray = JSONArray(responseStr)

                if (jsonArray.length() > 0) {
                    val firstObj = jsonArray.getJSONObject(0)
                    
                    val id = firstObj.optString("id", "").trim()
                    val prompt = firstObj.optString("prompt", "").trim()

                    if (id.isNotEmpty() && prompt.isNotEmpty()) {
                        Log.i(TAG, "POLLING: Found pending task: $id")
                        val task = InteractionTask(id = id, prompt = prompt, status = "pending")
                        
                        if (lockTask(task.id)) {
                            onNewTask(task)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "POLLING EXCEPTION: ${e.message}")
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

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode in 200..299) {
                val responseStr = readStream(connection.inputStream)
                val jsonArray = JSONArray(responseStr)
                val success = jsonArray.length() > 0
                if (success) Log.i(TAG, "LOCK: Task $taskId securely locked.")
                success
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "LOCK EXCEPTION: ${e.message}")
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

                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                if (connection.responseCode in 200..299) {
                    Log.i(TAG, "SUCCESS: Task ${task.id} updated on cloud.")
                } else {
                    Log.e(TAG, "DB ERROR: Failed to ack task ${task.id} - HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ACK EXCEPTION: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun readStream(inputStream: InputStream?): String {
        if (inputStream == null) return ""
        return try {
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
}
