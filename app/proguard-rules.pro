# ProGuard rules for AccountBook.
# Most of the heavy lifting is done by the default Android optimize file plus
# the consumer rules that ship with our libraries. Add app-specific rules here.

# Keep Room generated classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$* { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class nt.ddeoid.accountbook.**$$serializer { *; }
-keepclassmembers class nt.ddeoid.accountbook.** {
    *** Companion;
}
-keepclasseswithmembers class nt.ddeoid.accountbook.** {
    kotlinx.serialization.KSerializer serializer(...);
}