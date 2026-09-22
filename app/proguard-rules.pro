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
# Broadened 2026-09-22: kotlinx.coroutines pulls a second slf4j binder lookup
# (org.slf4j.impl.StaticMDCBinder, referenced from org.slf4j.MDC) once
# coroutines is fully kept for the releaseTest build — same well-known
# "slf4j-api present, no binding implementation, gracefully handled by
# slf4j's own try/catch at runtime" pattern as the rule above.
-dontwarn org.slf4j.impl.**

-keep class com.google.crypto.tink.** { *; }
-keep class lava.network.dto.** { *; }

# kotlin.LazyKt — real R8-strip found via the FIRST-EVER release-variant
# (releaseTest) Challenge Test execution, 2026-09-22:
# java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt at
# runtime on Challenge00CrashSurvivalTest, .lava-ci-evidence/
# 2026-09-22-client-releaseTest-c00/. R8's static analysis of the APP's own
# code alone considered kotlin.LazyKt (the `by lazy {}` delegate helper)
# unused and stripped it from the release-shaped APK; code the androidTest
# APK depends on at runtime still calls into it. Exactly the class of
# app-vs-test-APK R8 mismatch this project's release-variant test wiring
# exists to catch — see §6.Z's forensic anchor (the 1.2.19-1039
# painterResource layer-list crash) for the earlier instance of this pattern.
-keep class kotlin.LazyKt { *; }
# Broadened 2026-09-22: after kotlin.LazyKt, a SECOND distinct Kotlin-stdlib
# internal class was found stripped (kotlin.time.AbstractLongTimeSource,
# NoClassDefFoundError at runtime) once coroutines/test-support were kept
# above. Kotlin stdlib is small, foundational, and virtually always fully
# needed at runtime — R8's static reachability analysis is well-known to be
# unreliable for its internal/inline-heavy classes. Keep it broadly rather
# than continuing to find individual missing classes one whack-a-mole cycle
# at a time; the size cost of fully keeping kotlin-stdlib is negligible.
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# dagger.hilt.internal.Preconditions — second real R8-strip found by the same
# releaseTest run, immediately after the kotlin.LazyKt fix above:
# java.lang.NoSuchMethodError: No static method
# checkState(ZLjava/lang/String;[Ljava/lang/Object;)V in class
# Ldagger/hilt/internal/Preconditions — R8 kept a different overload of
# checkState but stripped the one the running Hilt-generated code actually
# calls. Hilt's own consumer proguard rules evidently don't fully cover this
# internal helper under isRemoveUnusedCode=true; keep the whole internal
# runtime package rather than one overload at a time, since finding these one
# NoSuchMethodError at a time is not a sustainable process.
-keep class dagger.hilt.internal.** { *; }

# Broad test-support keep (2026-09-22): the THIRD distinct R8-strip found in
# a row by the same releaseTest run (after kotlin.LazyKt and
# dagger.hilt.internal.Preconditions above) was
# java.lang.NoClassDefFoundError: androidx/test/espresso/IdlingResource.
# Finding these one class at a time is the well-known "whack-a-mole" pattern
# with minified-but-testable Android build types — these packages exist
# SOLELY to support instrumentation and cost nothing functionally for real
# users if fully kept, so keep them broadly rather than one missing class at
# a time.
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
-keep class androidx.compose.ui.test.** { *; }
# Broadened 2026-09-22: androidx.compose.ui.platform.InfiniteAnimationPolicy
# (a Compose test-framework animation-control hook, $DefaultImpls synthetic
# interface class) was ALSO found stripped. releaseTest never ships to a real
# user — it exists solely to execute Challenge tests against the R8-minified
# shape — so a broad keep of the whole Compose UI runtime costs nothing that
# matters here and stops finding individual Compose test-support classes one
# whack-a-mole cycle at a time.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class dagger.hilt.android.testing.** { *; }
-keep class dagger.hilt.** { *; }

# Hilt @EntryPoint generated code across the WHOLE app (2026-09-22): the
# SEVENTH distinct R8-strip in this cycle — java.lang.NoClassDefFoundError:
# lava/work/di/HiltWrapper_HiltWorkerFactoryEntryPoint. This project's Hilt
# @EntryPoint interfaces (e.g. Challenge00's own PersistenceEntryPoint) are
# reached ONLY via EntryPointAccessors.fromApplication(...) reflective
# lookups, invisible to R8's static reachability analysis, and this pattern
# is used pervasively across ~30 core/feature modules — finding each
# generated entry point one NoClassDefFoundError at a time is not
# sustainable. Broad, name-pattern-based keep covering Hilt's own codegen
# naming convention project-wide.
-keep class **.*EntryPoint* { *; }
-keep class **.Hilt_* { *; }
-keep class **.*_HiltModules { *; }
-keep class **.*_HiltComponents { *; }
-keep class **.HiltWrapper_* { *; }
-keep class **.*_GeneratedInjector { *; }
-keep,allowobfuscation @dagger.hilt.EntryPoint interface *

# kotlinx.coroutines — the FOURTH distinct R8-strip found in a row:
# java.lang.NoClassDefFoundError: kotlinx/coroutines/DelayWithTimeoutDiagnostics.
# A widely-known, commonly-recommended keep rule for any coroutines-using app
# under R8 full mode — coroutines relies on internal diagnostic/debug classes
# R8's static reachability analysis routinely misses. Keeping broadly here
# rather than continuing one internal class at a time.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

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
