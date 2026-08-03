# The WebView JS bridge is gone, and with it the only reflective entry point
# this app had. Compose and SQLDelight are both statically dispatched and need
# no keep rules of their own.
#
# kotlinx-serialization is the one thing here that does: the plugin generates
# serializer() companions that R8 cannot see being used. Keep them, or every
# backup export fails only in a release build — the build nobody tests until
# they ship it.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    *** Companion;
}
