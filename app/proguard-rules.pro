# Kanta R8 rules (spec §8: release build with R8)

# --- kotlinx.serialization (used by supabase-kt models) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class mk.kanta.app.**$$serializer { *; }
-keepclassmembers class mk.kanta.app.** {
    *** Companion;
}
-keepclasseswithmembers class mk.kanta.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor / OkHttp ---
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- MapLibre ---
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
