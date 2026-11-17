# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.celander.app.**$$serializer { *; }
-keepclassmembers class com.celander.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.celander.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance creates widget action callbacks by reflection (actionRunCallback<T>()).
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}

# Glance finds a widget's content by its GlanceAppWidget class name; merged or renamed classes
# would draw one widget's content into another.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
