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

/**
 * ARCHITECTURE CONTRACT: Supabase.kt
 * Role: The Nervous System (Network Singleton & Realtime Listener).
 * Constraints: Background thread execution ONLY. No UI references.
 */
object SupabaseManager {

    private const val TAG = "NeuroLink_NervousSystem"
    private const val TABLE_QUEUE = "interaction_queue"

    // WARNING: In production, inject these via BuildConfig or Secure Enclave.
    // Hardcoding here strictly for the micro-architecture template.
    private const val SUPABASE_URL = "https://aeopowovqksexgvseiyq.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_HX5GTYwHATs3gTksy-ZV9w_AQNIfM7t"

    private lateinit var client: SupabaseClient
    private val networkScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Initializes the Supabase client and starts the Realtime WebSocket connection.
     * @param onNewTask Callback triggered when a new 'pending' task arrives.
     */
    fun initializeNetworkListener(onNewTask: (InteractionTask) -> Unit) {
        Log.i(TAG, "SYSTEM BOOT: Initializing Supabase Client...")
        
        client = createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_KEY
        ) {
            install(Postgrest)
            install(Realtime)
        }

        networkScope.launch {
            try {
                // Connect WebSocket
                client.realtime.connect()
                Log.i(TAG, "REALTIME: WebSocket Connected.")
                
                listenForPendingTasks(onNewTask)
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Failed to connect Realtime - ${e.message}")
            }
        }
    }

    /**
     * Subscribes to database INSERT events where status is 'pending'.
     */
    private suspend fun listenForPendingTasks(onNewTask: (InteractionTask) -> Unit) {
        val channel = client.channel("public-$TABLE_QUEUE")

        val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public",
        ) {
            table = TABLE_QUEUE
            filter = "status=eq.pending"
        }

        changeFlow.onEach { action ->
            val record = action.record
            Log.i(TAG, "SIGNAL RECEIVED: New task detected in database.")
            
            // Map JSON response to Kotlin Data Class (api.kt)
            val task = InteractionTask(
                id = record["id"]?.jsonPrimitive?.content ?: return@onEach,
                prompt = record["prompt"]?.jsonPrimitive?.content ?: return@onEach,
                status = "pending"
            )

            // Lock the task immediately to prevent race conditions with other workers
            if (lockTask(task.id)) {
                onNewTask(task)
            }
        }.launchIn(networkScope)

        channel.subscribe()
    }

    /**
     * Row-Level Lock Simulation: Updates status to 'processing'.
     * @return true if successfully locked, false if another worker took it.
     */
    private suspend fun lockTask(taskId: String): Boolean {
        return try {
            client.postgrest[TABLE_QUEUE]
                .update({
                    set("status", "processing")
                }) {
                    filter {
                        InteractionTask::id eq taskId
                        InteractionTask::status eq "pending"
                    }
                }
            Log.i(TAG, "LOCK ACQUIRED: Task $taskId status -> processing")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LOCK FAILED: Task $taskId may have been claimed. ${e.message}")
            false
        }
    }

    /**
     * Handshake from MainActivity: Updates the database with the harvested AI response.
     */
    fun updateTaskAndAcknowledge(task: InteractionTask) {
        networkScope.launch {
            try {
                Log.i(TAG, "DISPATCHING: Sending harvested response to Database for Task ${task.id}")
                
                client.postgrest[TABLE_QUEUE]
                    .update({
                        set("response", task.response)
                        set("status", task.status) // 'COMPLETED' or 'FAILED'
                    }) {
                        filter { InteractionTask::id eq task.id }
                    }
                    
                Log.i(TAG, "TRANSACTION COMPLETE: Task ${task.id} finalized.")
            } catch (e: Exception) {
                Log.e(TAG, "TRANSACTION FAILED: Could not save response for Task ${task.id} - ${e.message}")
                // Implement retry queue logic here if network drops temporarily
            }
        }
    }
}
