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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
// CRITICAL: Required for extracting data from the Realtime JSON record
import kotlinx.serialization.json.jsonPrimitive

/**
 * ARCHITECTURE CONTRACT: SupabaseManager (The Nervous System)
 * Responsibility: Singleton for database interactions and Realtime event streaming.
 * Version: 2.1 (Optimized for Kotlin 1.9.22 + Supabase 2.0.0)
 */
object SupabaseManager {

    private const val TAG = "Kall_NervousSystem"
    private const val TABLE_QUEUE = "interaction_queue"

    // Replace with your actual credentials from Supabase Dashboard
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
            try {
                client.realtime.connect()
                Log.i(TAG, "REALTIME: WebSocket Secure Connected.")
                
                val channel = client.realtime.channel("public-queue")
                
                // Listening for INSERT events on interaction_queue
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(
                    schema = "public"
                ) {
                    table = TABLE_QUEUE
                }

                changeFlow.onEach { action ->
                    val record = action.record
                    Log.d(TAG, "EVENT: New row detected. Filtering for status=pending...")

                    // Check if status is pending before processing
                    val status = record["status"]?.jsonPrimitive?.content
                    if (status == "pending") {
                        val task = InteractionTask(
                            id = record["id"]?.jsonPrimitive?.content ?: "",
                            prompt = record["prompt"]?.jsonPrimitive?.content ?: "",
                            status = "pending"
                        )

                        if (task.id.isNotEmpty()) {
                            // Atomic Lock: Ensure this worker claims the task
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
    }

    /**
     * ROW-LEVEL LOCKING: Atomically updates status to 'processing'.
     * Uses Postgrest DSL for v2.0.0.
     */
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

    /**
     * FINAL HANDSHAKE: Update result and status to DB.
     */
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
