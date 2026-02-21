package com.kall

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Added BOOT_IMMORTALITY_SCRIPT for Extreme Deep-Sleep Prevention & Network Keep-Alive.
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

    // 🚨 NEW HACK: ऐप खुलते ही WebView को अमर बनाने और नेटवर्क को ज़िंदा रखने की स्क्रिप्ट
    val BOOT_IMMORTALITY_SCRIPT = """
        (function() {
            // 1. IMMORTALITY HACK: स्टार्ट होते ही Audio Context चालू करें (Deep Sleep रोकेगा)
            if (!window.audioHackActive) {
                window.audioHackActive = true;
                try {
                    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                    const oscillator = audioCtx.createOscillator();
                    const gainNode = audioCtx.createGain();
                    gainNode.gain.value = 0; // 100% Silent
                    oscillator.connect(gainNode);
                    gainNode.connect(audioCtx.destination);
                    oscillator.start();
                    console.log("SYSTEM: Boot-level Audio Hack Active. WebView is immortal.");
                } catch(e) { console.log(e); }
            }

            // 2. NETWORK KEEP-ALIVE: हर 15 सेकंड में बैकग्राउंड में हलचल करें ताकि वाई-फाई स्लीप मोड में न जाए
            if (!window.heartbeatActive) {
                window.heartbeatActive = setInterval(() => {
                    console.log("SYSTEM: Heartbeat Pulse - Keeping network socket open...");
                    document.body.dispatchEvent(new Event('mousemove', { bubbles: true }));
                }, 15000);
            }

            // 3. AUTO-HEALER: अगर पेज पर नेटवर्क एरर आए, तो खुद ही रीलोड करवा दे
            if (!window.errorObserverActive) {
                window.errorObserverActive = true;
                const observer = new MutationObserver(() => {
                    const text = document.body.innerText.toLowerCase();
                    if (text.includes('network error') || text.includes('failed to fetch') || text.includes('no internet')) {
                        console.log("SYSTEM: Network disconnection detected in UI!");
                        window.AndroidBridge.onError('DOM_ERROR: AI Page Network Timeout, Self-Healing required...');
                        observer.disconnect(); 
                    }
                });
                observer.observe(document.body, { childList: true, subtree: true });
            }
        })();
    """.trimIndent()


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
                    
                    const fullText = "$safePrompt";
                    const chunkSize = 2048; 
                    const chunks = [];
                    for (let i = 0; i < fullText.length; i += chunkSize) {
                        chunks.push(fullText.substring(i, i + chunkSize));
                    }
                    
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        setter.call(inputEl, "");
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
                                setter.call(inputEl, inputEl.value + chunkText);
                            } else {
                                inputEl.innerHTML += chunkText;
                            }
                            
                            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                            
                            currentChunkIndex++;
                            setTimeout(injectNextChunk, 50); 
                        } else {
                            finalizeInjection();
                        }
                    }

                    function finalizeInjection() {
                        inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                        
                        setTimeout(() => {
                            let possibleBtns = Array.from(document.querySelectorAll('button')).filter(b => !b.disabled && b.querySelector('svg'));
                            let sendBtn = document.querySelector('button[aria-label*="send" i], button[data-testid*="send" i], button.send-btn') 
                                          || possibleBtns[possibleBtns.length - 1]; 
                            
                            if (sendBtn) {
                                sendBtn.click();
                                window.AndroidBridge.onInjectionSuccess('SUCCESS: Heavy Payload chunked & clicked');
                            } else {
                                window.AndroidBridge.onError('DOM_ERROR: Send button completely hidden');
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

    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null;
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, .qwen-ui-message, div[data-message-author="assistant"], div[class*="content"]');
                    
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponseEl = responseBlocks[responseBlocks.length - 1];
                    let latestResponse = latestResponseEl.innerText.trim();
                    
                    if (latestResponse === '[]' || latestResponse === '' || latestResponse === '...' || latestResponse === '[\n]') return; 
                    
                    if (!isTyping) {
                        if (latestResponse === lastContent) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
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

