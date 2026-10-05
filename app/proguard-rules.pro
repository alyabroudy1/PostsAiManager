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

# Release logging: strip verbose/debug/info logs (they carry document ids and timings).
# Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
