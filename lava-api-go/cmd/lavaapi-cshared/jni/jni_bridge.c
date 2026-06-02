/*
 * jni_bridge.c — JNI adaptation of the c-shared lava-api-go embed.
 *
 * This file bridges the flat C ABI exported by main.go (LavaApiStart /
 * LavaApiStop / LavaApiStatus / LavaApiFree, declared in the cgo-generated
 * liblavaapi.h) to the JNI naming convention the Kotlin side binds.
 *
 * KOTLIN CONTRACT (Phase C MUST match this exactly):
 *
 *   package digital.vasic.lava.apigo
 *
 *   object LavaNative {
 *       external fun nativeStart(configJson: String): String  // "" on success, else error message
 *       external fun nativeStop(): String                     // "" on success, else error message
 *       external fun nativeStatus(): String                   // status JSON document
 *   }
 *
 * The JNI symbol name encodes package + class + method:
 *   Java_<package-with-dots-as-underscores>_<ClassName>_<methodName>
 * so package `digital.vasic.lava.apigo`, object `LavaNative`, method
 * `nativeStart` => Java_digital_vasic_lava_apigo_LavaNative_nativeStart.
 *
 * (An `object` in Kotlin compiles to a final class with a static INSTANCE; its
 * `external` functions are registered as static-from-JNI's-perspective native
 * methods, so the JNI signature receives a jclass, not a jobject. The bridge
 * functions below therefore take `jclass clazz`.)
 *
 * Ownership: every string the Go side returns is heap-allocated by C.CString.
 * We copy it into a JVM string via (*env)->NewStringUTF and then immediately
 * release the C buffer via LavaApiFree so nothing leaks across the boundary.
 *
 * Build note: this file deliberately lives in cmd/lavaapi-cshared/jni/ (NOT in
 * the cgo `main` package) because it #includes liblavaapi.h, the cgo-generated
 * header that `go build -buildmode=c-shared` PRODUCES. If it sat alongside
 * main.go, cgo would try to compile it before that header exists. It is built
 * later by the Android NDK toolchain via the sibling CMakeLists.txt, which adds
 * the per-ABI prebuilt directory (holding liblavaapi.{so,h}) to the include +
 * link path.
 */

#include <jni.h>
#include <stddef.h>

#include "liblavaapi.h"

/*
 * Java_digital_vasic_lava_apigo_LavaNative_nativeStart
 * Kotlin: external fun nativeStart(configJson: String): String
 */
JNIEXPORT jstring JNICALL
Java_digital_vasic_lava_apigo_LavaNative_nativeStart(JNIEnv *env, jclass clazz, jstring configJson) {
    (void)clazz;

    const char *cfg = (*env)->GetStringUTFChars(env, configJson, NULL);
    if (cfg == NULL) {
        /* OutOfMemoryError already pending in the JVM. */
        return NULL;
    }

    /* LavaApiStart casts away const internally (C.GoString copies). */
    char *result = LavaApiStart((char *)cfg);

    (*env)->ReleaseStringUTFChars(env, configJson, cfg);

    jstring jresult = (*env)->NewStringUTF(env, result != NULL ? result : "");
    LavaApiFree(result);
    return jresult;
}

/*
 * Java_digital_vasic_lava_apigo_LavaNative_nativeStop
 * Kotlin: external fun nativeStop(): String
 */
JNIEXPORT jstring JNICALL
Java_digital_vasic_lava_apigo_LavaNative_nativeStop(JNIEnv *env, jclass clazz) {
    (void)clazz;

    char *result = LavaApiStop();
    jstring jresult = (*env)->NewStringUTF(env, result != NULL ? result : "");
    LavaApiFree(result);
    return jresult;
}

/*
 * Java_digital_vasic_lava_apigo_LavaNative_nativeStatus
 * Kotlin: external fun nativeStatus(): String
 */
JNIEXPORT jstring JNICALL
Java_digital_vasic_lava_apigo_LavaNative_nativeStatus(JNIEnv *env, jclass clazz) {
    (void)clazz;

    char *result = LavaApiStatus();
    jstring jresult = (*env)->NewStringUTF(env, result != NULL ? result : "");
    LavaApiFree(result);
    return jresult;
}
