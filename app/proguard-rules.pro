# Compose/Material3 classes
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.postsaimanager.**$$serializer { *; }
-keepclassmembers class com.postsaimanager.** {
    *** Companion;
}
-keepclasseswithmembers class com.postsaimanager.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Hilt
-dontwarn dagger.hilt.**

# ONNX Runtime (search model): its native code looks up Java classes, fields and methods by name
# (OrtSession.run → convertOrtValueToONNXValue → GetMethodID), so R8 must not rename or strip any of them.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# JNI: keep every native method and its declaring class; keep the llama.cpp bridge whole, since its
# native side may call back into Java (cancel/progress hooks) by name.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.postsaimanager.core.ai.local.** { *; }

# LiteRT-LM (the Gallery's engine): liblitertlm_jni.so looks up its Kotlin classes, fields and methods by name (the engine,
# conversation, message callbacks, configs, tool types), and its tool layer reads Kotlin metadata by reflection, so R8 must neither
# rename nor strip any of them. The AAR ships no consumer rules of its own.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.postsaimanager.core.ai.litert.** { *; }
# Agent Skills tools (LoadSkillTool, RunIntentTool in core.ai.litert.tools): LiteRT-LM's `tool(...)` finds the @Tool methods and
# their @ToolParam parameters with kotlin-reflect, which reads the class's kotlin.Metadata (function and parameter names) and the
# runtime-visible annotations. The names the model sees (load_skill, run_intent) come from the method names, so R8 may neither
# rename the methods nor drop the metadata or the annotations. (The keep above already covers the tool classes and their members.)
-keep class kotlin.Metadata { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.ToolSet {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
-keepattributes *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, InnerClasses, EnclosingMethod
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
# Gson (a LiteRT-LM dependency) builds its JSON model of Message/Tool types by reflection.
-keepattributes Signature
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# JS skills: the offline WebView calls back into `AiEdgeGallery.onResultReady` (an @JavascriptInterface method) by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Release logging: strip verbose/debug/info logs (they carry document ids and timings).
# Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
