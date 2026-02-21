package com.kall

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

// 🚨 ब्रह्मास्त्र: Direct HTTP API के लिए Android के इनबिल्ट पैकेजेस
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

/**
 * ARCHITECTURE CONTRACT: SupabaseManager (The Nervous System)
 * Version: 3.0 (BYPASSED SUPABASE-KT COMPILATION ERRORS USING DIRECT REST API)
 */
object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    private const val TABLE_QUEUE = "ai_tasks"

    private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"

    private lateinit var client: SupabaseClient
    private val networkScope = CoroutineScope(Dispatchers.IO + Job())

    fun initializeNetworkListener(onNewTask: (InteractionTask) -> Unit) {
        Log.i(TAG, "SYSTEM BOOT: Initializing Supabase Connection...")
        
        client = createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_KEY
        ) {
            install(Postgrest)
            install(Realtime)
        }

        // ==========================================
        // METHOD 1: REALTIME (तुम्हारे ओरिजिनल कोड के अनुसार)
        // ==========================================
        networkScope.launch {
            try {
                client.realtime.connect()
                Log.i(TAG, "REALTIME: WebSocket Secure Connected.")
                
                val channel = client.realtime.channel("public-ai-tasks")
                
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(
                    schema = "public"
                ) {
                    table = TABLE_QUEUE
                }

                changeFlow.onEach { action ->
                    val record = action.record
                    val status = record["status"]?.jsonPrimitive?.content
                    if (status == "pending") {
                        val task = InteractionTask(
                            id = record["id"]?.jsonPrimitive?.content ?: "",
                            prompt = record["prompt"]?.jsonPrimitive?.content ?: "",
                            status = "pending"
                        )
                        if (task.id.isNotEmpty()) {
                            if (lockTask(task.id)) {
                                onNewTask(task)
                            }
                        }
                    }
                }.launchIn(this)

                channel.subscribe()
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Connectivity lost in Nervous System - ${e.message}")
            }
        }

        // ==========================================
        // METHOD 2: POLLING FALLBACK (DIRECT HTTP REST API - NO COMPILATION ERRORS)
        // ==========================================
        networkScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    // Supabase-kt लाइब्रेरी को बायपास करके सीधे URL पर कॉल मारेंगे
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
                                if (lockTask(task.id)) {
                                    Log.i(TAG, "POLLING: Picked up pending task directly via REST API.")
                                    onNewTask(task)
                                }
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    // साइलेंट इग्नोर ताकि लूप चलता रहे
                }
                delay(3000) // हर 3 सेकंड में चेक करेगा
            }
        }
    }

    // ==========================================
    // 100% ORIGINAL WORKING SYNTAX (No changes here)
    // ==========================================
    private suspend fun lockTask(taskId: String): Boolean {
        return try {
            client.postgrest[TABLE_QUEUE].update({
                set("status", "processing")
            }) {
                filter {
                    eq("id", taskId)
                    eq("status", "pending")
                }
            }
            Log.i(TAG, "LOCK: Task $taskId is now mine.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LOCK ERROR: Task $taskId might be taken - ${e.message}")
            false
        }
    }

    fun updateTaskAndAcknowledge(task: InteractionTask) {
        networkScope.launch {
            try {
                client.postgrest[TABLE_QUEUE].update({
                    set("response", task.response)
                    set("status", task.status)
                }) {
                    filter { eq("id", task.id) }
                }
                Log.i(TAG, "SUCCESS: Task ${task.id} finalized in cloud.")
            } catch (e: Exception) {
                Log.e(TAG, "DB ERROR: Failed to acknowledge task ${task.id} - ${e.message}")
            }
        }
    }
}
