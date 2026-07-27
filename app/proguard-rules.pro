# kotlinx.serialization keeps its serializers in synthetic members that R8 can't see are used.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.metromusic.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.metromusic.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.metromusic.data.**$$serializer { *; }
