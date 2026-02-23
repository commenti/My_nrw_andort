package com.kall

import android.util.Base64
import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Architecture: Employs Universal SPA Event Simulation to bypass React/Vue Virtual DOM restrictions.
 */

// ==========================================
// 1. DATA MODELS
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

    val BOOT_IMMORTALITY_SCRIPT = """
        (function() {
            if (!window.audioHackActive) {
                window.audioHackActive = true;
                try {
                    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                    const oscillator = audioCtx.createOscillator();
                    const gainNode = audioCtx.createGain();
                    gainNode.gain.value = 0; 
                    oscillator.connect(gainNode);
                    gainNode.connect(audioCtx.destination);
                    oscillator.start();
                } catch(e) {}
            }

            if (!window.heartbeatActive) {
                window.heartbeatActive = setInterval(() => {
                    document.body.dispatchEvent(new Event('mousemove', { bubbles: true }));
                }, 15000);
            }
        })();
    """.trimIndent()

    fun buildDispatchScript(rawPrompt: String): String {
        // Base64 encode to prevent JSON/String escaping syntax errors in JS payload
        val base64Prompt = Base64.encodeToString(rawPrompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        return """
            (function() {
                try {
                    const b64 = "$base64Prompt";
                    const binString = atob(b64);
                    const bytes = new Uint8Array(binString.length);
                    for (let i = 0; i < binString.length; i++) { bytes[i] = binString.charCodeAt(i); }
                    const fullText = new TextDecoder('utf-8').decode(bytes);

                    // 1. Locate the active input area
                    const textareas = Array.from(document.querySelectorAll('textarea, [contenteditable="true"]'));
                    const inputEl = textareas.find(el => el.offsetWidth > 0 && el.offsetHeight > 0 && !el.disabled);
                    
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: No interactive input element found.');
                        return;
                    }

                    inputEl.focus();

                    // 2. Universal React/Vue Virtual DOM Bypass
                    const isTextarea = inputEl.tagName.toLowerCase() === 'textarea';
                    
                    if (isTextarea) {
                        // React 16+ Native Setter Hack
                        const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeInputValueSetter.call(inputEl, fullText);
                    } else {
                        // ContentEditable Fallback
                        inputEl.innerHTML = '';
                        document.execCommand('insertText', false, fullText);
                    }

                    // 3. Fire complete event lifecycle to wake up SPA state manager
                    const events = ['input', 'change', 'compositionend', 'keyup'];
                    events.forEach(eventType => {
                        inputEl.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                    });

                    // 4. Locate and trigger Send Button
                    setTimeout(() => {
                        let sendBtn = null;
                        
                        // Strategy A: Standard selectors
                        const standardSelectors = [
                            'button[data-testid*="send"]', 
                            'button[aria-label*="send" i]',
                            'button[aria-label*="Send" i]'
                        ];
                        for (let sel of standardSelectors) {
                            sendBtn = document.querySelector(sel);
                            if (sendBtn && !sendBtn.disabled) break;
                        }

                        // Strategy B: Proximity Traversal (Find button in the same container as input)
                        if (!sendBtn || sendBtn.disabled) {
                            let parent = inputEl.parentElement;
                            let attempts = 0;
                            while (parent && attempts < 5) {
                                const btns = Array.from(parent.querySelectorAll('button'));
                                // Filter out disabled buttons and attachments
                                const activeBtns = btns.filter(b => !b.disabled && b.offsetWidth > 0);
                                if (activeBtns.length > 0) {
                                    // Usually the send button is the last interactive button in the input row
                                    sendBtn = activeBtns[activeBtns.length - 1];
                                    break;
                                }
                                parent = parent.parentElement;
                                attempts++;
                            }
                        }

                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.focus();
                            sendBtn.click();
                            window.AndroidBridge.onInjectionSuccess('SUCCESS: Payload injected and dispatched.');
                        } else {
                            // Fallback: Trigger Enter key if button is utterly hidden
                            inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                            window.AndroidBridge.onInjectionSuccess('WARNING: Send button not found. Dispatched Enter key event instead.');
                        }
                    }, 800); // 800ms delay allows SPA to process the input event and enable the button

                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            // Standardize selectors for LLM outputs
            const contentSelectors = '.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]';
            const initialBlocks = document.querySelectorAll(contentSelectors);
            const initialContent = initialBlocks.length > 0 ? initialBlocks[initialBlocks.length - 1].innerText.trim() : '';
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    // Check typing indicators
                    const allSpansAndBtns = Array.from(document.querySelectorAll('button, span, div'));
                    const isThinking = allSpansAndBtns.some(el => el.innerText && el.innerText.toLowerCase().trim() === 'thinking');
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null || isThinking;
                    
                    const responseBlocks = document.querySelectorAll(contentSelectors);
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponseEl = responseBlocks[responseBlocks.length - 1];
                    let latestResponse = latestResponseEl.innerText.trim();
                    
                    if (!latestResponse || latestResponse === '...' || latestResponse === initialContent) {
                        stabilityCounter = 0; 
                        return; 
                    }
                    
                    if (!isTyping) {
                        if (latestResponse === lastContent) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        // Wait for 3 seconds of absolute DOM stability (3 ticks)
                        if (stabilityCounter >= 3) {
                            clearInterval(window.activeHarvester);
                            window.activeHarvester = null;
                            
                            // Aggressive JSON extraction fallback
                            let finalOutput = latestResponse;
                            const jsonRegex = /```(?:json)?\s*([\s\S]*?)```/i;
                            const match = latestResponse.match(jsonRegex);
                            
                            if (match && match[1]) {
                                finalOutput = match[1].trim(); 
                            } else {
                                const rawJsonMatch = latestResponse.match(/(\{[\s\S]*\}|\[[\s\S]*\])/);
                                if (rawJsonMatch && rawJsonMatch[0]) {
                                    finalOutput = rawJsonMatch[0].trim();
                                }
                            }
                            
                            window.AndroidBridge.onResponseHarvested(finalOutput);
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
