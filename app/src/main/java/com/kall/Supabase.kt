package com.kall

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
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

/**
 * ARCHITECTURE CONTRACT: SupabaseManager (The Nervous System)
 * Version: 2.5 (Final Compilation Fix - Polling Logic Refactored)
 */
object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    [span_5](start_span)private const val TABLE_QUEUE = "ai_tasks"[span_5](end_span)

    [span_6](start_span)[span_7](start_span)private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"[span_6](end_span)[span_7](end_span)
    [span_8](start_span)[span_9](start_span)private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"[span_8](end_span)[span_9](end_span)

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

        networkScope.launch {
            
            // ==========================================
            // METHOD 1: REALTIME (WebSocket Listener)
            // ==========================================
            launch {
                try {
                    client.realtime.connect()
                    Log.i(TAG, "REALTIME: WebSocket Secure Connected.")
                    
                    val channel = client.realtime.channel("public-ai-tasks")
                    val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
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
                            handlePendingTask(task, onNewTask)
                        }
                    }.launchIn(this)

                    channel.subscribe()
                } catch (e: Exception) {
                    Log.e(TAG, "REALTIME ERROR: ${e.message}")
                }
            }

            // ==========================================
            // METHOD 2: POLLING FALLBACK (Robust Heartbeat)
            // ==========================================
            launch {
                while(true) {
                    try {
                        // Polling: Manually fetch pending tasks if WebSocket drops
                        val response = client.postgrest[TABLE_QUEUE]
                            .select(Columns.ALL) {
                                filter { eq("status", "pending") }
                            }
                        
                        val pendingTasks = response.decodeList<InteractionTask>()

                        if (pendingTasks.isNotEmpty()) {
                            Log.i(TAG, "POLLING: Found ${pendingTasks.size} tasks. Processing first...")
                            handlePendingTask(pendingTasks.first(), onNewTask)
                        }
                    } catch (e: Exception) {
                        // Silent catch to prevent loop breakage
                    }
                    delay(3000) // Poll every 3 seconds
                }
            }
        }
    }

    private suspend fun handlePendingTask(task: InteractionTask, onNewTask: (InteractionTask) -> Unit) {
        if (task.id.isNotEmpty()) {
            if (lockTask(task.id)) {
                onNewTask(task)
            }
        }
    }

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
            Log.i(TAG, "LOCK: Task $taskId claimed.")
            true
        } catch (e: Exception) {
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
                Log.i(TAG, "SUCCESS: Task ${task.id} finalized.")
            } catch (e: Exception) {
                Log.e(TAG, "DB UPDATE ERROR: ${e.message}")
            }
        }
    }
}
