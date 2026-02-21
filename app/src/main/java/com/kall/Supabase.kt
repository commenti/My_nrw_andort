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
 * Version: 2.8 (Restored ORIGINAL perfectly working update syntax)
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

        networkScope.launch {
            
            // ==========================================
            // METHOD 1: REALTIME (WebSocket Listener)
            // ==========================================
            launch {
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
                    Log.e(TAG, "REALTIME ERROR: ${e.message}")
                }
            }

            // ==========================================
            // METHOD 2: POLLING FALLBACK (Robust Heartbeat)
            // ==========================================
            launch {
                while(true) {
                    try {
                        // Polling with exact simple syntax
                        val response = client.postgrest[TABLE_QUEUE].select {
                            filter { eq("status", "pending") }
                        }
                        
                        val pendingTasks = response.decodeList<InteractionTask>()

                        if (pendingTasks.isNotEmpty()) {
                            val task = pendingTasks.first()
                            if (task.id.isNotEmpty()) {
                                if (lockTask(task.id)) {
                                    onNewTask(task)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silent catch to keep loop alive
                    }
                    delay(3000) // Poll every 3 seconds
                }
            }
        }
    }

    // ORIGINAL WORKING SYNTAX RESTORED BELOW
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
