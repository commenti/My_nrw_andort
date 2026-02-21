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

    fun buildDispatchScript(rawPrompt: String): String {
        val safePrompt = rawPrompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        return """
            (function() {
                try {
                    let inputEl = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: Input box not found');
                        return;
                    }
                    
                    // 🚨 HACKER FIX 1: Native Typing Simulation
                    inputEl.value = ''; // Clear existing
                    inputEl.focus();
                    document.execCommand('insertText', false, "$safePrompt");
                    
                    setTimeout(() => {
                        // 🚨 HACKER FIX 2: Dynamic Button Mapping (Like a hacker)
                        // Qwen मोबाइल पर सेंड बटन अक्सर एक SVG आइकॉन होता है जो टाइप करने के बाद एक्टिव होता है।
                        let possibleBtns = Array.from(document.querySelectorAll('button')).filter(b => !b.disabled && b.querySelector('svg'));
                        
                        let sendBtn = document.querySelector('button[aria-label*="send" i], button[data-testid*="send" i], button.send-btn') 
                                      || possibleBtns[possibleBtns.length - 1]; // Fallback: Last active button with an icon
                        
                        if (sendBtn) {
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload clicked dynamically');
                        } else {
                            window.AndroidBridge.onError('DOM_ERROR: Send button completely hidden');
                        }
                    }, 1000); // 1-second delay to let Qwen UI animations finish
                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator') !== null;
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, .qwen-ui-message');
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponse = responseBlocks[responseBlocks.length - 1].innerText;
                    
                    if (!isTyping) {
                        if (latestResponse === lastContent && latestResponse.trim().length > 0) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        if (stabilityCounter >= 3) {
                            clearInterval(window.activeHarvester);
                            window.activeHarvester = null;
                            window.AndroidBridge.onResponseHarvested(latestResponse);
                        }
                    } else {
                        stabilityCounter = 0;
                        lastContent = latestResponse;
                    }
                } catch (e) {
                    clearInterval(window.activeHarvester);
                    window.AndroidBridge.onError('HARVEST_ERROR: ' + e.message);
                }
            }, 1000);
        })();
    """.trimIndent()
}
