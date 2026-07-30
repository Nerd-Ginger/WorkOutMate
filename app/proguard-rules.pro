# The JS bridge is reached reflectively from the WebView, so the annotated
# methods and the class holding them must survive shrinking and renaming.
-keepclassmembers class com.nerdginger.workoutmate.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.nerdginger.workoutmate.NativeBridge { *; }
