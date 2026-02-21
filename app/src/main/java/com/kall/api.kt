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
                    // 1. Smart Selector: Textarea या ContentEditable Div ढूँढो (Mobile Layout Support)
                    let inputEl = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: Input box not found on this mobile layout');
                        return;
                    }
                    
                    // 2. Text Inject करो
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeInputValueSetter.call(inputEl, "$safePrompt");
                    } else {
                        inputEl.innerText = "$safePrompt";
                    }
                    
                    // 3. Framework को बताओ कि टाइपिंग हुई है
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                    inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    // 4. Send Button दबाओ या Enter मारो (Fallback)
                    setTimeout(() => {
                        const sendBtn = document.querySelector('button[type="submit"], button[aria-label*="end"], button[data-testid*="send"], .ant-btn-primary');
                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload dispatched via Click');
                        } else {
                            // Fallback: अगर सेंड बटन न मिले, तो Enter की दबा दो
                            const enterEvent = new KeyboardEvent('keydown', {
                                bubbles: true, cancelable: true, keyCode: 13, key: 'Enter'
                            });
                            inputEl.dispatchEvent(enterEvent);
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload dispatched via Enter');
                        }
                    }, 800);
                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) {
                clearInterval(window.activeHarvester);
            }
            
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

