# Shrinking is disabled for release (engine uses reflection everywhere). Rules kept for safety.
-keep class top.niunaijun.blackbox.** { *; }
-keep class mirror.** { *; }
-keep class com.dualspace.clone.** { *; }
-dontwarn android.**
