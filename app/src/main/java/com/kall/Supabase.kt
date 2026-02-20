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
 * Version: 2.3 (Added Polling Fallback to prevent Silent WebSocket Drops)
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
            // METHOD 1: REALTIME (घंटी बजने का इंतज़ार)
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
                    Log.e(TAG, "FATAL: Connectivity lost in Nervous System - ${e.message}")
                }
            }

            // ==========================================
            // METHOD 2: POLLING FALLBACK (हर 3 सेकंड में चेक करना)
            // ==========================================
            launch {
                while(true) {
                    try {
                        // डेटाबेस से 'pending' स्टेटस वाले टास्क मांगो
                        val pendingTasks = client.postgrest[TABLE_QUEUE]
                            .select { filter { eq("status", "pending") } }
                            .decodeList<InteractionTask>()

                        // अगर कोई टास्क मिला, तो उसे प्रोसेस करो
                        if (pendingTasks.isNotEmpty()) {
                            handlePendingTask(pendingTasks.first(), onNewTask)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "POLLING ERROR: ${e.message}") // साइलेंट इग्नोर ताकि लूप न टूटे
                    }
                    delay(3000) // 3 सेकंड का इंतज़ार
                }
            }
        }
    }

    // DRY Principle: दोनों मेथड इसी फंक्शन का इस्तेमाल करेंगे
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
