package com.kall

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Optimized for Modern React/Vue UIs (Qwen) & evaluateJavascript compatibility.
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
     * Injects prompt into the DOM.
     * Uses Native Input Setter hack to bypass React/Vue state management restrictions.
     */
    fun buildDispatchScript(rawPrompt: String): String {
        val safePrompt = rawPrompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        // CRITICAL FIX: Removed 'javascript:' prefix. Not needed for evaluateJavascript.
        return """
            (function() {
                try {
                    const textarea = document.querySelector('textarea');
                    if (!textarea) {
                        window.AndroidBridge.onError('DOM_ERROR: Textarea not found');
                        return;
                    }
                    
                    // REACT/VUE HACK: Force the native setter so the framework registers the input
                    const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                    nativeInputValueSetter.call(textarea, "$safePrompt");
                    
                    // Dispatch synthetic events
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    // Wait for the UI to enable the send button, then click it
                    setTimeout(() => {
                        // Generic selectors covering Ant Design (Qwen), Tailwind, and standard UI kits
                        const sendBtn = document.querySelector('button[type="submit"], button[aria-label*="end"], button[data-testid*="send"], .ant-btn-primary');
                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload dispatched');
                        } else {
                            window.AndroidBridge.onError('DOM_ERROR: Send button not found or disabled');
                        }
                    }, 800); // 800ms debounce gives React enough time to update button state
                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    /**
     * Smart Mutation Harvester.
     * Watches the DOM and waits for the AI response to fully stabilize before capturing.
     */
    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) {
                clearInterval(window.activeHarvester);
            }
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    // Check if AI is still explicitly generating (Stop button exists)
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator') !== null;
                    
                    // Qwen typically uses markdown-body or message-content
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, .qwen-ui-message');
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponse = responseBlocks[responseBlocks.length - 1].innerText;
                    
                    if (!isTyping) {
                        // STABILITY CHECK: Ensure content hasn't changed in the last 3 seconds
                        if (latestResponse === lastContent && latestResponse.trim().length > 0) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        // If stable for 3 polling cycles (3 seconds), consider it done
                        if (stabilityCounter >= 3) {
                            clearInterval(window.activeHarvester);
                            window.activeHarvester = null;
                            window.AndroidBridge.onResponseHarvested(latestResponse);
                        }
                    } else {
                        // Reset stability counter if still typing
                        stabilityCounter = 0;
                        lastContent = latestResponse;
                    }
                } catch (e) {
                    clearInterval(window.activeHarvester);
                    window.AndroidBridge.onError('HARVEST_ERROR: ' + e.message);
                }
            }, 1000); // Poll DOM every 1000ms
        })();
    """.trimIndent()
}
