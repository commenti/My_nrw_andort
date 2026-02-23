package com.kall

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ARCHITECTURE CONTRACT: SupabaseManager (The Nervous System)
 * Version: 4.0 (100% PURE REST API - NO EXTERNAL SUPABASE LIBRARIES NEEDED)
 * Logic: Bypasses all Gradle/Compilation errors using Android's native HttpURLConnection.
 */
object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    private const val TABLE_QUEUE = "ai_tasks"

    private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"

    private val networkScope = CoroutineScope(Dispatchers.IO + Job())

    fun initializeNetworkListener(onNewTask: (InteractionTask) -> Unit) {
        Log.i(TAG, "SYSTEM BOOT: Initializing 100% Pure REST Polling...")

        // ==========================================
        // 🚨 PURE HTTP POLLING (NO WEBSOCKET CRASHES)
        // ==========================================
        networkScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?status=eq.pending")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("apikey", SUPABASE_KEY)
                    connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                    connection.setRequestProperty("Accept", "application/json")
                    
                    if (connection.responseCode == 200) {
                        val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(responseStr)
                        
                        if (jsonArray.length() > 0) {
                            val firstObj = jsonArray.getJSONObject(0)
                            val task = InteractionTask(
                                id = firstObj.optString("id", ""),
                                prompt = firstObj.optString("prompt", ""),
                                status = "pending"
                            )
                            
                            if (task.id.isNotEmpty()) {
                                // टास्क को लॉक करो (ताकि कोई और वर्कर न उठा ले)
                                if (lockTask(task.id)) {
                                    Log.i(TAG, "POLLING: Picked up pending task directly via REST API.")
                                    onNewTask(task)
                                }
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    // साइलेंट इग्नोर (अगर इंटरनेट बंद हो तो ऐप क्रैश न हो)
                }
                delay(2500) // हर 2.5 सेकंड में नया टास्क चेक करेगा
            }
        }
    }

    // ==========================================
    // 🚨 NATIVE HTTP PATCH (TASK LOCKING)
    // ==========================================
    private fun lockTask(taskId: String): Boolean {
        return try {
            val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?id=eq.$taskId&status=eq.pending")
            val connection = url.openConnection() as HttpURLConnection
            
            // HTTP PATCH जुगाड़ (Android के लिए)
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("apikey", SUPABASE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")
            connection.doOutput = true

            // सुरक्षित JSON बॉडी
            val jsonBody = JSONObject()
            jsonBody.put("status", "processing")

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray())
            }

            val isSuccess = if (connection.responseCode in 200..299) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseStr)
                jsonArray.length() > 0 // अगर Row अपडेट हुई है तो true
            } else {
                false
            }
            
            connection.disconnect()
            
            if (isSuccess) Log.i(TAG, "LOCK: Task $taskId is now MINE.")
            isSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "LOCK ERROR: Task $taskId might be taken - ${e.message}")
            false
        }
    }

    // ==========================================
    // 🚨 NATIVE HTTP PATCH (TASK COMPLETION)
    // ==========================================
    fun updateTaskAndAcknowledge(task: InteractionTask) {
        networkScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/$TABLE_QUEUE?id=eq.${task.id}")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connection.setRequestProperty("apikey", SUPABASE_KEY)
                connection.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                // सुरक्षित JSON बॉडी (Response के साथ)
                val jsonBody = JSONObject()
                jsonBody.put("status", task.status)
                if (task.response != null) {
                    jsonBody.put("response", task.response)
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray())
                }

                if (connection.responseCode in 200..299) {
                    Log.i(TAG, "SUCCESS: Task ${task.id} finalized in cloud.")
                } else {
                    Log.e(TAG, "DB ERROR: Failed to acknowledge task ${task.id} - HTTP ${connection.responseCode}")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "DB ERROR: ${e.message}")
            }
        }
    }
}

