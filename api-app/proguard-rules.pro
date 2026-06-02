# :api-app ProGuard / R8 rules.
#
# Keep the embedded Go c-shared JNI surface reachable. The native bridge
# (liblavaapi_jni.so) resolves lava.apiengine.LavaNative methods by their
# JNI signature at runtime; R8 must not rename or strip them.
-keep class lava.apiengine.** { *; }

# Keep the foreground Service + its intent-action handling reachable by name
# (started via component name / actions from the app and the OS).
-keep class lava.api.app.service.** { *; }
