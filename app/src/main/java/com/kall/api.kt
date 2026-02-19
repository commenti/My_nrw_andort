package com.kall

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 */

// ==========================================
// 1. DATA MODELS (SUPABASE CONTRACTS)
// ==========================================

@Serializable
data class InteractionTask(
    val id: String,
    val prompt: String,
    val status: String,
    val response: String? = null
)

// ==========================================
// 2. JAVASCRIPT INJECTION PROTOCOLS
// ==========================================

object JsInjector {

    /**
     * Injects prompt into the DOM and triggers Synthetic Events for React/Vue based UIs.
     * Prevents literal string breaks via regex sanitization.
     */
    fun buildDispatchScript(rawPrompt: String): String {
        val safePrompt = rawPrompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        return """
            javascript:(function() {
                try {
                    const textarea = document.querySelector('textarea');
                    if (!textarea) {
                        window.AndroidBridge.onError('DOM_ERROR: Textarea not found');
                        return;
                    }
                    
                    // Set value
                    textarea.value = "$safePrompt";
                    
                    // Dispatch React/Vue synthetic events
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    // Find and click the submission button (Fallback generic selectors used)
                    setTimeout(() => {
                        const sendBtn = document.querySelector('button[aria-label*="end"], button[data-testid*="send"], .send-button');
                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload dispatched');
                        } else {
                            window.AndroidBridge.onError('DOM_ERROR: Send button not found or disabled');
                        }
                    }, 500); // 500ms debounce for UI state update
                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    /**
     * Mutation Observer Protocol.
     * Watches the DOM for the completion of the AI streaming response.
     */
    val HARVESTER_SCRIPT = """
        javascript:(function() {
            // Prevent duplicate observers
            if (window.activeHarvester) {
                clearInterval(window.activeHarvester);
            }
            
            window.activeHarvester = setInterval(() => {
                try {
                    // Logic: If 'Stop generating' button exists, AI is still typing.
                    const isTyping = document.querySelector('button[aria-label*="Stop"]') !== null;
                    
                    if (!isTyping) {
                        clearInterval(window.activeHarvester);
                        window.activeHarvester = null;
                        
                        // Extract the latest response block
                        // Update '.markdown-body' or '.prose' based on Qwen's specific DOM classes
                        const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content');
                        
                        if (responseBlocks.length > 0) {
                            const latestResponse = responseBlocks[responseBlocks.length - 1].innerText;
                            window.AndroidBridge.onResponseHarvested(latestResponse);
                        } else {
                            window.AndroidBridge.onError('HARVEST_ERROR: Response container not found');
                        }
                    }
                } catch (e) {
                    clearInterval(window.activeHarvester);
                    window.AndroidBridge.onError('HARVEST_ERROR: ' + e.message);
                }
            }, 1000); // Poll DOM every 1000ms
        })();
    """.trimIndent()
}
