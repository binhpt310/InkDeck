# Custom Views are inflated by name from XML.
-keep class dev.inkdeck.eink.widget.** { *; }
-keep class dev.inkdeck.ui.** { *; }
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
