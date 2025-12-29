# ProGuard rules for DaLang
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
