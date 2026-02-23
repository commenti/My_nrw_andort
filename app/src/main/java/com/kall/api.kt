package com.kall

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Architecture Aligned for Android 15+ with Chunked Injection & Deep Sleep Prevention.
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

    // 🚨 1. IMMORTALITY SCRIPT: WebView को ज़िंदा रखने का हैक
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
                    console.log("SYSTEM: Boot-level Audio Hack Active.");
                } catch(e) { console.log(e); }
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

    // 🚨 2. CHUNKED INJECTION SCRIPT: ट्रक को छोटे पैकेट में डालकर भेजना
    fun buildDispatchScript(rawPrompt: String): String {
        // प्रॉम्प्ट को JavaScript के लिए सुरक्षित बनाना
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
                    const chunkSize = 1024; // 1024 कैरेक्टर्स का सुरक्षित चंक
                    const chunks = [];
                    for (let i = 0; i < fullText.length; i += chunkSize) {
                        chunks.push(fullText.substring(i, i + chunkSize));
                    }
                    
                    // बॉक्स खाली करना (React/Vue तरीके से)
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        setter.call(inputEl, "");
                    } else {
                        inputEl.innerHTML = "";
                    }
                    inputEl.focus();

                    let currentChunkIndex = 0;

                    // चंक-वाइज़ इंजेक्शन लॉजिक (UI को फ्रीज़ होने से बचाने के लिए)
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
                            
                            // React/Vue को जगाने के लिए इवेंट्स फायर करना
                            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                            
                            currentChunkIndex++;
                            setTimeout(injectNextChunk, 20); // हर 20ms में अगला हिस्सा डालो
                        } else {
                            finalizeInjection();
                        }
                    }

                    function finalizeInjection() {
                        inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                        
                        setTimeout(() => {
                            // सेंड बटन ढूंढना और दबाना
                            let possibleBtns = Array.from(document.querySelectorAll('button')).filter(b => !b.disabled && b.querySelector('svg'));
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

                    injectNextChunk(); // प्रक्रिया शुरू करें

                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    // 🚨 3. DATA EXTRACTION SCRIPT: शुद्ध JSON बाहर निकालना
    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    // Qwen के टाइपिंग इंडिकेटर्स और स्टॉप बटन्स चेक करना
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null;
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]');
                    
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponseEl = responseBlocks[responseBlocks.length - 1];
                    let latestResponse = latestResponseEl.innerText.trim();
                    
                    if (latestResponse === '[]' || latestResponse === '' || latestResponse === '...') return; 
                    
                    if (!isTyping) {
                        // अगर 5 सेकंड तक आउटपुट नहीं बदलता, तो मतलब AI का काम खत्म (Stability Lock)
                        if (latestResponse === lastContent) {
                            stabilityCounter++;
                        } else {
                            stabilityCounter = 0;
                            lastContent = latestResponse;
                        }
                        
                        if (stabilityCounter >= 5) {
                            clearInterval(window.activeHarvester);
                            window.activeHarvester = null;
                            
                            // REGEX: सिर्फ JSON को काटना
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
            }, 1000); // हर 1 सेकंड में चेक करेगा
        })();
    """.trimIndent()
}
