package digital.vasic.lava.apigo

/**
 * JNI binding to the embedded lava-api-go c-shared library.
 *
 * The package, object name, and function names MUST match the JNI symbol
 * encoding in `lava-api-go/cmd/lavaapi-cshared/jni/jni_bridge.c` EXACTLY:
 *
 *   Java_digital_vasic_lava_apigo_LavaNative_nativeStart  ⇐ this package + object + fun
 *
 * Do NOT rename or move this object without updating the C bridge in lockstep.
 *
 * The native methods are loaded from `liblavaapi_jni.so` (the CMake target
 * built by the sibling CMakeLists.txt), which in turn links the prebuilt
 * `liblavaapi.so`. `System.loadLibrary("lavaapi_jni")` resolves the `lib`
 * prefix + `.so` suffix and loads the JNI bridge; its transitive dependency on
 * `liblavaapi.so` is resolved by the linker because both libraries are
 * packaged into the same `jniLibs/<abi>/` directory of the APK.
 *
 * @see [lava.apiengine.NativeApiEngine] for the high-level API that wraps these
 *   raw native calls.
 */
internal object LavaNative {
    init {
        // liblavaapi.so is loaded transitively as a NEEDED dependency of
        // liblavaapi_jni.so; loading the JNI bridge is sufficient.
        System.loadLibrary("lavaapi_jni")
    }

    /** Returns "" on success, or the error message. Arg is the config JSON. */
    external fun nativeStart(configJson: String): String

    /** Returns "" on success, or the error message. */
    external fun nativeStop(): String

    /** Returns the Status JSON document. */
    external fun nativeStatus(): String
}
