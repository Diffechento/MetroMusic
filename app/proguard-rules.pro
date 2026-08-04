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

# jaudiotagger does its ID3 work through two pieces of reflection and R8 defeats both, which is why
# editing an album's tags worked in every debug build and did nothing but report errors on a real
# installed release. Neither failure looks like a missing class.
#
# Frame bodies are loaded **by name** — `Class.forName("org.jaudiotagger.tag.id3.framebody.FrameBody"
# + frameId)`, in AbstractID3v2Frame and ID3v22Frame. Nothing in the app ever names FrameBodyTPE1, so
# R8 renames or deletes all of them (190 classes went), and the library can then no longer build the
# body for a field it has been asked to write. What the user sees is
# `Field with key of:TPE1:does not accept cannot parse data:<the value>` — the message `createField`
# ends on when its instanceof chain finds no body it recognises, so it reads as the value being at
# fault, and it names whichever field happened to be written first.
-keep class org.jaudiotagger.tag.id3.framebody.** { *; }

# Frames and datatypes are also duplicated through a **copy constructor** looked up as
# `getClass().getConstructor(getClass())` (`ID3Tags.copyObject`), which nothing calls directly, so R8
# removes it — 523 constructors in this library went, that one among them. The class object survives
# renaming; the constructor has to survive shrinking.
-keepclassmembers class org.jaudiotagger.** {
    <init>(...);
}
