# JNI bridge: native code looks these up by name.
-keep class com.localtranscribe.whisper.** { *; }

# Listener implementations are called back from JNI by method name.
-keep class * implements com.localtranscribe.whisper.WhisperLib$Listener { *; }
-keepclassmembers class * implements com.localtranscribe.whisper.WhisperLib$Listener {
    void onProgress(int);
    void onSegment(long, long, java.lang.String);
}

# Models are read as plain JSON.
-keep class org.json.** { *; }

-dontwarn org.jetbrains.annotations.**
