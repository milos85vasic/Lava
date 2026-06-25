# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn org.bouncycastle.jsse.*
-dontwarn org.bouncycastle.jsse.provider.*
-dontwarn org.conscrypt.*
-dontwarn org.openjsse.javax.net.ssl.*
-dontwarn org.openjsse.net.ssl.*
-dontwarn org.slf4j.impl.StaticLoggerBinder

-keep class com.google.crypto.tink.** { *; }
-keep class lava.network.dto.** { *; }

# kotlinx.serialization keep rules — added 2026-06-25 after the prod
# 1.3.11(1075) RELEASE crash on Settings → provider → "Sync this provider"
# toggle: "Serializer for class 'WireToggle' is not found". Root cause was
# the missing serialization compiler plugin in feature/provider_config (now
# applied via id("lava.kotlin.serialization")); R8 release would ALSO strip
# the generated $serializer companions even with the plugin, because nothing
# referenced them reflectively from kept code. These rules keep the
# @Serializable wire classes + their generated $serializer for the
# provider-config feature (WireToggle / WireBinding / WireMirror are private
# nested classes of ProviderConfigViewModel → ProviderConfigViewModel$Wire*)
# and any other @Serializable type, mirroring the kotlinx-serialization
# consumer rules. §11.4.146 reproduce-first; Crashlytics eaa80c1486d2d5d7526346ece016e15a.

# Keep the kotlinx-serialization runtime + the generated companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep @Serializable classes and their synthesized Companion / $serializer.
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
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}

# Targeted belt-and-braces keep for the provider-config feature's wire
# classes (the prod-crash surface) + their generated serializers.
-keep,includedescriptorclasses class lava.provider.config.**$$serializer { *; }
-keepclassmembers class lava.provider.config.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase keep rules — added 2026-05-05 after operator reported 2
# Crashlytics-recorded crashes within minutes of the first Firebase-
# instrumented release distribution. The Firebase BOM ships consumer
# ProGuard rules but the operator-observed crashes implicate R8
# stripping of Firebase reflective entry points. These rules harden
# Crashlytics + Analytics + Performance against R8 minification.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.measurement.** { *; }
-keep class com.google.android.gms.internal.measurement.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile
