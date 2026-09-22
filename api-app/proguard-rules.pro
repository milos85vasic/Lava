# :api-app ProGuard / R8 rules.
#
# Keep the embedded Go c-shared JNI surface reachable. The native bridge
# (liblavaapi_jni.so) resolves lava.apiengine.LavaNative methods by their
# JNI signature at runtime; R8 must not rename or strip them.
-keep class lava.apiengine.** { *; }

# Keep the foreground Service + its intent-action handling reachable by name
# (started via component name / actions from the app and the OS).
-keep class lava.api.app.service.** { *; }

# releaseTest test-support keeps (2026-09-22), preemptively mirrored from
# app/proguard-rules.pro's real, discovered-one-at-a-time findings — api-app
# shares the same Hilt + Compose + coroutines + androidx.test stack, so the
# same 8 categories of R8-strip are near-certain to recur here too. See
# app/proguard-rules.pro for the individual forensic anchors (each one was a
# genuine java.lang.NoClassDefFoundError / NoSuchMethodError found by
# actually running a release-variant Challenge test).
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.impl.**
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
# okhttp3 (2026-09-22): api-app's OWN production code does NOT use OkHttp —
# the on-device API is a Go embed (see the dependencies{} comment above) —
# but the androidTest helper OnDeviceApiClient.kt makes real HTTPS calls via
# OkHttp to exercise the served API, and OkHttp only appears transitively
# (pulled in by firebase-perf) so R8 never sees it as reachable from
# production code and strips it. Genuine NoClassDefFoundError found running
# Challenge02/03/04 against releaseTest.
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class dagger.hilt.** { *; }
-keep class **.*EntryPoint* { *; }
-keep class **.Hilt_* { *; }
-keep class **.*_HiltModules { *; }
-keep class **.*_HiltComponents { *; }
-keep class **.HiltWrapper_* { *; }
-keep class **.*_GeneratedInjector { *; }
-keep,allowobfuscation @dagger.hilt.EntryPoint interface *
