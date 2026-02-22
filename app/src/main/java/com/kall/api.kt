package com.kall // 🚨 FIX: 'p' छोटा कर दिया है!

import kotlinx.serialization.Serializable

/**
 * ARCHITECTURE CONTRACT: api.kt
 * Role: Data Models
 * Constraints: No Android Context, No State, No Network calls.
 * UPDATE: Removed JS Injectors because we are now using the Native Accessibility Robot! 🤖
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

// 🚨 नोट: JsInjector को डिलीट कर दिया गया है क्योंकि अब उसकी कोई ज़रूरत नहीं है।

