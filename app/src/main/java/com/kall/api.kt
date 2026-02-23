package com.kall

import android.util.Base64
import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models & JavaScript Injection Utilities (Stateless)
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Added React/Vue Virtual DOM Bypass Hack to ensure text is visible in WebView.
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
        })();
    """.trimIndent()

    // 🚨 2. CHUNKED INJECTION SCRIPT (WITH REACT.JS BYPASS)
    fun buildDispatchScript(rawPrompt: String): String {
        // प्रॉम्प्ट को Base64 में एन्कोड करें ताकि JSON का कोई ब्रैकेट JS को क्रैश न करे
        val base64Prompt = Base64.encodeToString(rawPrompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        return """
            (function() {
                try {
                    // 🚨 HACK 1: सिर्फ वो डिब्बा ढूंढो जो स्क्रीन पर दिखाई दे रहा हो (Hidden को इग्नोर करो)
                    const textareas = Array.from(document.querySelectorAll('textarea, [contenteditable="true"]'));
                    const inputEl = textareas.find(el => el.offsetWidth > 0 && el.offsetHeight > 0);
                    
                    if (!inputEl) {
                        window.AndroidBridge.onError('DOM_ERROR: Visible Input box not found');
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
                    
                    inputEl.focus();

                    // 🚨 HACK 2: React.js Bypass Setter (यह React को बेवकूफ बनाएगा)
                    function setNativeValue(element, value) {
                        if (element.tagName.toLowerCase() === 'textarea') {
                            const valueSetter = Object.getOwnPropertyDescriptor(element, 'value').set;
                            const prototype = Object.getPrototypeOf(element);
                            const prototypeValueSetter = Object.getOwnPropertyDescriptor(prototype, 'value').set;
                            
                            if (valueSetter && valueSetter !== prototypeValueSetter) {
                                prototypeValueSetter.call(element, value);
                            } else {
                                valueSetter.call(element, value);
                            }
                        } else {
                            // ContentEditable के लिए
                            document.execCommand('selectAll', false, null);
                            document.execCommand('insertText', false, value);
                        }
                    }

                    // पहले डिब्बा साफ करो
                    setNativeValue(inputEl, "");
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));

                    let currentChunkIndex = 0;

                    function injectNextChunk() {
                        if (currentChunkIndex < chunks.length) {
                            window.AndroidBridge.onChunkProgress(currentChunkIndex + 1, chunks.length);
                            const chunkText = chunks[currentChunkIndex];
                            
                            // चंक को जोड़ो और React को बताओ कि टाइपिंग हुई है
                            if (inputEl.tagName.toLowerCase() === 'textarea') {
                                setNativeValue(inputEl, inputEl.value + chunkText);
                            } else {
                                // कर्सर को आखिर में ले जाकर इंसर्ट करो
                                const selection = window.getSelection();
                                selection.selectAllChildren(inputEl);
                                selection.collapseToEnd();
                                document.execCommand('insertText', false, chunkText);
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
                            // 🚨 सेंड बटन ढूंढने का स्मार्ट तरीका
                            let btns = Array.from(document.querySelectorAll('button')).filter(b => b.offsetWidth > 0 && !b.disabled);
                            let sendBtn = btns.find(b => {
                                const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                                const testId = (b.getAttribute('data-testid') || '').toLowerCase();
                                return aria.includes('send') || testId.includes('send');
                            });
                            
                            if (!sendBtn) {
                                // अगर नाम से नहीं मिला, तो आखिरी SVG वाला बटन सेंड होता है
                                let svgBtns = btns.filter(b => b.querySelector('svg'));
                                sendBtn = svgBtns[svgBtns.length - 1];
                            }
                            
                            if (sendBtn) {
                                sendBtn.click();
                                window.AndroidBridge.onInjectionSuccess('SUCCESS: Heavy Payload chunked & clicked');
                            } else {
                                window.AndroidBridge.onError('DOM_ERROR: Visible Send button not found');
                            }
                        }, 1500); // टाइपिंग के बाद 1.5 सेकंड रुको ताकि सेंड बटन एक्टिव हो जाए
                    }

                    injectNextChunk();

                } catch (e) {
                    window.AndroidBridge.onError('EXECUTION_ERROR: ' + e.message);
                }
            })();
        """.trimIndent()
    }

    // 🚨 3. HARVESTER SCRIPT (Patience Lock)
    val HARVESTER_SCRIPT = """
        (function() {
            if (window.activeHarvester) clearInterval(window.activeHarvester);
            
            const initialBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]');
            const initialContent = initialBlocks.length > 0 ? initialBlocks[initialBlocks.length - 1].innerText.trim() : '';
            
            let lastContent = '';
            let stabilityCounter = 0;
            
            window.activeHarvester = setInterval(() => {
                try {
                    const allSpansAndBtns = Array.from(document.querySelectorAll('button, span, div'));
                    const isThinking = allSpansAndBtns.some(el => el.innerText && el.innerText.toLowerCase().trim() === 'thinking');
                    const isTyping = document.querySelector('button[aria-label*="Stop"], .typing-indicator, [class*="typing"]') !== null || isThinking;
                    
                    const responseBlocks = document.querySelectorAll('.markdown-body, .prose, .message-content, div[data-message-author="assistant"], div[class*="content"]');
                    if (responseBlocks.length === 0) return;
                    
                    const latestResponseEl = responseBlocks[responseBlocks.length - 1];
                    let latestResponse = latestResponseEl.innerText.trim();
                    
                    if (latestResponse === '[]' || latestResponse === '' || latestResponse === '...') return; 
                    
                    if (latestResponse === initialContent) {
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

