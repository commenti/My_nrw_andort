package com.kall

import android.util.Base64
import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Added "Patience Lock" & "Thinking Detector" to stop harvesting old messages!
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

    // 🚨 1. IMMORTALITY SCRIPT (WebView को ज़िंदा रखने का हैक)
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

            if (!window.errorObserverActive) {
                window.errorObserverActive = true;
                const observer = new MutationObserver(() => {
                    const text = document.body.innerText.toLowerCase();
                    if (text.includes('network error') || text.includes('failed to fetch')) {
                        window.AndroidBridge.onError('DOM_ERROR: Network Timeout Detected');
                        observer.disconnect(); 
                    }
                });
                observer.observe(document.body, { childList: true, subtree: true });
            }
        })();
    """.trimIndent()

    // 🚨 2. CHUNKED INJECTION SCRIPT (बड़े डेटा को सुरक्षित डालना)
    fun buildDispatchScript(rawPrompt: String): String {
        val base64Prompt = Base64.encodeToString(rawPrompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        return """
            (function() {
                try {
                    let inputEl = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: Input box not found');
                        return;
                    }
                    
                    const b64 = "$base64Prompt";
                    const binString = atob(b64);
                    const bytes = new Uint8Array(binString.length);
                    for (let i = 0; i < binString.length; i++) {
                        bytes[i] = binString.charCodeAt(i);
                    }
                    const fullText = new TextDecoder('utf-8').decode(bytes);
                    
                    const chunkSize = 1024; 
                    const chunks = [];
                    for (let i = 0; i < fullText.length; i += chunkSize) {
                        chunks.push(fullText.substring(i, i + chunkSize));
                    }
                    
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        if (setter) setter.call(inputEl, "");
                    } else {
                        inputEl.innerHTML = "";
                    }
                    inputEl.focus();

                    let currentChunkIndex = 0;

                    function injectNextChunk() {
                        if (currentChunkIndex < chunks.length) {
                            window.AndroidBridge.onChunkProgress(currentChunkIndex + 1, chunks.length);
                            const chunkText = chunks[currentChunkIndex];
                            
                            if (inputEl.tagName.toLowerCase() === 'textarea') {
                                const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                                if (setter) setter.call(inputEl, inputEl.value + chunkText);
                            } else {
                                inputEl.innerHTML += chunkText;
                            }
                            
                            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                            currentChunkIndex++;
                            setTimeout(injectNextChunk, 20); 
                        } else {
                            finalizeInjection();
                        }
                    }

                    function finalizeInjection() {
                        inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                        
                        setTimeout(() => {
                            let possibleBtns = Array.from(document.querySelectorAll('button')).filter(b => !b.disabled && (b.querySelector('svg') || b.innerText.toLowerCase().includes('send')));
                            let sendBtn = document.querySelector('button[aria-label*="send" i], button[data-testid*="send" i]') 
                                          || possibleBtns[possibleBtns.length - 1]; 
                            
                            if (sendBtn) {
                                sendBtn.click();
                                window.AndroidBridge.onInjectionSuccess('SUCCESS: Heavy Payload chunked & clicked');
                            } else {
                                window.AndroidBridge.onError('DOM_ERROR: Send button not active');
                            }
                        }, 1000);
                    }

                    injectNextChunk();

                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    // 🚨 3. HARVESTER SCRIPT (WITH PATIENCE LOCK & THINKING DETECTOR)
    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            // 🔒 PATIENCE LOCK: शुरू होते ही सबसे आखिरी (पुराने) मैसेज को याद कर लो
            const initialBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]');
            const initialContent = initialBlocks.length > 0 ? initialBlocks[initialBlocks.length - 1].innerText.trim() : '';
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    // 🧠 Qwen के "Thinking" बटन और "Typing" स्टेट को पकड़ना
                    const allSpansAndBtns = Array.from(document.querySelectorAll('button, span, div'));
                    const isThinking = allSpansAndBtns.some(el => el.innerText && el.innerText.toLowerCase().trim() === 'thinking');
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null || isThinking;
                    
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]');
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponseEl = responseBlocks[responseBlocks.length - 1];
                    let latestResponse = latestResponseEl.innerText.trim();
                    
                    if (latestResponse === '[]' || latestResponse === '' || latestResponse === '...') return; 
                    
                    // 🚨 CRITICAL FIX: अगर नया मैसेज अभी भी पुराने वाले जैसा ही है, तो मतलब AI ने सोचना शुरू नहीं किया है। इंतज़ार करो!
                    if (latestResponse === initialContent) {
                        stabilityCounter = 0; 
                        return; // लूप यहीं से वापस लौट जाएगा
                    }
                    
                    if (!isTyping) {
                        if (latestResponse === lastContent) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        // 5 सेकंड की स्टेबिलिटी (AI ने टाइपिंग पूरी कर ली है)
                        if (stabilityCounter >= 5) {
                            clearInterval(window.activeHarvester);
                            window.activeHarvester = null;
                            
                            let finalJsonOutput = latestResponse;
                            const jsonRegex = /```(?:json)?\s*([\s\S]*?)```/i;
                            const match = latestResponse.match(jsonRegex);
                            
                            if (match && match[1]) {
                                finalJsonOutput = match[1].trim(); 
                            } else {
                                const rawJsonMatch = latestResponse.match(/(\{[\s\S]*\}|\[[\s\S]*\])/);
                                if (rawJsonMatch && rawJsonMatch[0]) {
                                    finalJsonOutput = rawJsonMatch[0].trim();
                                }
                            }
                            
                            window.AndroidBridge.onResponseHarvested(finalJsonOutput);
                        }
                    } else {
                        stabilityCounter = 0; // टाइपिंग/थिंकिंग चल रही है, तो काउंटर रिसेट करो
                        lastContent = latestResponse;
                    }
                } catch (e) {
                    clearInterval(window.activeHarvester);
                    window.AndroidBridge.onError('HARVEST_ERROR: ' + e.message);
                }
            }, 1000); // हर 1 सेकंड में चेक करेगा
        })();
    """.trimIndent()
}

