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
                    
                    // 🚨 HACKER FIX 1: Bulletproof Injection (No weird symbols)
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        // React Native Setter for Textareas
                        const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeInputValueSetter.call(inputEl, "$safePrompt");
                    } else {
                        // Safe injection for ContentEditable Divs (Mobile UI)
                        inputEl.innerHTML = "$safePrompt";
                        inputEl.textContent = "$safePrompt"; 
                    }
                    
                    // Trigger events so the Send button lights up
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                    inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    setTimeout(() => {
                        let possibleBtns = Array.from(document.querySelectorAll('button')).filter(b => !b.disabled && b.querySelector('svg'));
                        let sendBtn = document.querySelector('button[aria-label*="send" i], button[data-testid*="send" i], button.send-btn') 
                                      || possibleBtns[possibleBtns.length - 1]; 
                        
                        if (sendBtn) {
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload clicked dynamically');
                        } else {
                            window.AndroidBridge.onError('DOM_ERROR: Send button completely hidden');
                        }
                    }, 1000); 
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
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null;
                    
                    // 🚨 HACKER FIX 2: Broader Mobile CSS Selectors
                    // यह अब मोबाइल UI के हर तरह के रिस्पॉन्स बॉक्स को पढ़ लेगा
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, .qwen-ui-message, div[data-message-author="assistant"], div[class*="content"]');
                    
                    if (responseBlocks.length === 0) return; // Wait until response UI appears
                    
                    const latestResponse = responseBlocks[responseBlocks.length - 1].innerText;
                    
                    if (!isTyping && latestResponse.trim().length > 0) {
                        if (latestResponse === lastContent) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        // 3 सेकंड तक जवाब न बदले, तो उसे फाइनल मान लो
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
