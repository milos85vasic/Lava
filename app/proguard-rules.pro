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
