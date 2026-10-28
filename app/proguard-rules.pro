-keep class com.hatchstudio.archive.core.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn org.bouncycastle.**
