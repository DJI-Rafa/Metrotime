# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Moshi ────────────────────────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonQualifier @interface *
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Kotlin metadata for Moshi reflection adapter
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# ── Retrofit ─────────────────────────────────────────────────────────────────
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# ── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── GTFS Realtime Bindings (Protobuf) ────────────────────────────────────────
-keep class com.google.transit.realtime.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ── API Models (keep for Moshi/Retrofit deserialization) ─────────────────────
-keep class com.example.metrotime.data.ApiModels** { *; }
-keep class com.example.metrotime.data.MetroLisboaArrivalResponse { *; }
-keep class com.example.metrotime.data.MetroLisboaPlatformArrival { *; }
-keep class com.example.metrotime.data.** { *; }

# ── MapLibre ─────────────────────────────────────────────────────────────────
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# ── Google Play Services ─────────────────────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**