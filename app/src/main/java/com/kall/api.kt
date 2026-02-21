package com.kall

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Added Chunked Injection (for heavy payloads) & Smart JSON Extraction.
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
        // 🚨 प्रॉम्प्ट को सुरक्षित बनाना (Escaping for JS)
        val safePrompt = rawPrompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        // 🚨 HACKER FIX 1: Cursor-wise / Chunked Injection Logic
        return """
            (function() {
                try {
                    let inputEl = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: Input box not found');
                        return;
                    }
                    
                    const fullText = "$safePrompt";
                    const chunkSize = 2048; // एक बार में सिर्फ 2048 कैरेक्टर्स डालेंगे ताकि UI फ्रीज़ न हो
                    const chunks = [];
                    for (let i = 0; i < fullText.length; i += chunkSize) {
                        chunks.push(fullText.substring(i, i + chunkSize));
                    }
                    
                    // Box को क्लियर करें
                    if (inputEl.tagName.toLowerCase() === 'textarea') {
                        const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        setter.call(inputEl, "");
                    } else {
                        inputEl.innerHTML = "";
                    }
                    inputEl.focus();

                    let currentChunkIndex = 0;

                    // Recursive function to inject chunks slowly (Asynchronous)
                    function injectNextChunk() {
                        if (currentChunkIndex < chunks.length) {
                            // Android Logcat को प्रोग्रेस बताओ
                            window.AndroidBridge.onChunkProgress(currentChunkIndex + 1, chunks.length);
                            
                            const chunkText = chunks[currentChunkIndex];
                            
                            if (inputEl.tagName.toLowerCase() === 'textarea') {
                                const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                                setter.call(inputEl, inputEl.value + chunkText);
                            } else {
                                inputEl.innerHTML += chunkText;
                            }
                            
                            // Frameworks (React/Vue) को जगाओ
                            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                            
                            currentChunkIndex++;
                            // 50ms का गैप दें ताकि ब्राउज़र का Main Thread "सांस" ले सके
                            setTimeout(injectNextChunk, 50); 
                        } else {
                            // Injection पूरा हुआ, अब Send बटन दबाएं
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

                    // Start the chunking process
                    injectNextChunk();

                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

        // 🚨 HACKER FIX 2: Structured Data Extraction + Silent Audio Hack
    val HARVESTER_SCRIPT = """
        (function() {
            // 🚨 IMMORTALITY HACK 2: Silent Web Audio (Prevents Background Throttling)
            if (!window.audioHackActive) {
                window.audioHackActive = true;
                try {
                    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                    const oscillator = audioCtx.createOscillator();
                    const gainNode = audioCtx.createGain();
                    gainNode.gain.value = 0; // 100% Silent (0 Volume)
                    oscillator.connect(gainNode);
                    gainNode.connect(audioCtx.destination);
                    oscillator.start();
                    console.log("SYSTEM: Audio Hack Active. WebView is now immortal.");
                } catch(e) { console.log(e); }
            }

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
