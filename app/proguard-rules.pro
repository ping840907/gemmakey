# Keep LiteRT-LM and AICore native entry points
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.android.ai.edge.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
