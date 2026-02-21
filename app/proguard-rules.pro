# ARCHITECTURE CONTRACT: proguard-rules.pro
# Role: Code Shrinking, Obfuscation, and Optimization.
# Constraints: Must protect JavaScript Bridge and Supabase Serialization.
# UPDATE: Fixed NeuroBridge path for the new standalone Leak-Proof architecture.

# ---------------------------------------------------------
# 1. KOTLIN & COROUTINES
# ---------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext {
    java.lang.String name;
}

# ---------------------------------------------------------
# 2. ANDROIDX & WEBVIEW (CRITICAL)
# ---------------------------------------------------------
# 🚨 SECURITY FIX: Protect the standalone JavaScript Interface from being renamed or stripped.
-keep class com.kall.NeuroBridge { *; }
-keepclassmembers class com.kall.NeuroBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------
# 3. KOTLINX SERIALIZATION (api.kt Models)
# ---------------------------------------------------------
# Required for @Serializable classes to work after obfuscation.
-keepgroup kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers class com.kall.InteractionTask {
    *** Companion;
}
-keep @kotlinx.serialization.Serializable class com.kall.** { *; }

# ---------------------------------------------------------
# 4. SUPABASE & KTOR
# ---------------------------------------------------------
# Keep Ktor engines and Supabase client internals.
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.serialization.json.internal.**

# ---------------------------------------------------------
# 5. GENERAL OPTIMIZATION
# ---------------------------------------------------------
-repackageclasses ''
-allowaccessmodification
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
