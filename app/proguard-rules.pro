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
