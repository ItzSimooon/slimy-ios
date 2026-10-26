# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization: keep generated serializers of our own types.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class de.hyprnode.slimy.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.hyprnode.slimy.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class de.hyprnode.slimy.** { *; }
