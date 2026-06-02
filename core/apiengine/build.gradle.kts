// core:apiengine — Android library exposing the embedded lava-api-go server
// (a Go c-shared native library) behind the Kotlin `ApiEngine` API (Phase C of
// the Lava API Android app plan).
//
// ─── Native wiring approach ───────────────────────────────────────────────
// Two native libraries end up packaged per ABI in this module's AAR:
//
//   1. liblavaapi.so       — the prebuilt Go c-shared library. Produced by
//                            `lava-api-go/scripts/build-cshared.sh`, which
//                            cross-compiles per ABI into
//                            lava-api-go/build/jniLibs/<abi>/liblavaapi.{so,h}.
//                            We DO NOT recompile Go from Gradle; we LOCATE the
//                            script output and stage it as a jniLibs source dir.
//   2. liblavaapi_jni.so   — the hand-written JNI bridge (jni_bridge.c), built
//                            here by AGP's externalNativeBuild (CMake), which
//                            imports liblavaapi.so as an IMPORTED SHARED lib and
//                            links the bridge against it. The CMakeLists.txt is
//                            the canonical one at
//                            lava-api-go/cmd/lavaapi-cshared/jni/CMakeLists.txt.
//
// Chosen reproducible wiring:
//   • A `buildCshared` Gradle task invokes build-cshared.sh for the project's
//     ABIs so the prebuilt .so/.h exist before merge/compile. It is up-to-date
//     when all expected outputs are present (no forced Go rebuild every time).
//   • `sourceSets.main.jniLibs.srcDir(...)` points at the script's output dir
//     so AGP packages liblavaapi.so per ABI directly.
//   • `externalNativeBuild.cmake.path` points at the canonical CMakeLists.txt;
//     `LAVAAPI_PREBUILT_DIR` is passed as a CMake arg so it finds the prebuilt
//     .so/.h, and `ndkVersion`/`abiFilters` are pinned to the three built ABIs.
//   • The native-build tasks dependsOn `buildCshared` so the prebuilt inputs
//     exist before CMake configures.
@file:Suppress("UnstableApiUsage")

import org.gradle.internal.os.OperatingSystem

plugins {
    id("lava.android.library")
    // kotlinx-serialization compiler plugin: NativeApiEngine serializes
    // `@Serializable` ConfigDto/StatusDto via `Json.encodeToString` /
    // `decodeFromString`. Without this plugin no serializers are generated and
    // the on-device embed-start throws `Serializer for class 'ConfigDto' is not
    // found` at runtime (caught by the Phase E real-device Challenge).
    id("lava.kotlin.serialization")
}

// Absolute paths to the lava-api-go c-shared build script + its output layout.
val lavaApiGoDir = rootProject.file("lava-api-go")
val cSharedScript = File(lavaApiGoDir, "scripts/build-cshared.sh")
// build-cshared.sh writes <abi>/liblavaapi.{so,h} here.
val prebuiltJniLibsDir = File(lavaApiGoDir, "build/jniLibs")
// The canonical JNI bridge CMakeLists.txt + source.
val jniCmakeLists = File(lavaApiGoDir, "cmd/lavaapi-cshared/jni/CMakeLists.txt")

// ABIs this module ships. Matches the build script's defaults.
val supportedAbis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")

// Task that ensures the prebuilt Go c-shared .so/.h exist for every ABI.
// It shells out to the existing, anti-bluff build script (which reports real
// build output and verifies the exported symbols). Up-to-date when all
// expected outputs are already present, so a clean assemble does not force a
// multi-minute Go rebuild when the artifacts are fresh.
val buildCshared by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the lava-api-go embed into per-ABI liblavaapi.so via build-cshared.sh"

    inputs.file(cSharedScript)
    supportedAbis.forEach { abi ->
        outputs.file(File(prebuiltJniLibsDir, "$abi/liblavaapi.so"))
        outputs.file(File(prebuiltJniLibsDir, "$abi/liblavaapi.h"))
    }

    workingDir = lavaApiGoDir
    if (OperatingSystem.current().isWindows) {
        // The Go c-shared/JNI path is not supported on a Windows build host;
        // surface that honestly rather than producing a broken artifact.
        commandLine("cmd", "/c", "echo", "build-cshared.sh is not supported on Windows && exit 1")
    } else {
        commandLine("bash", cSharedScript.absolutePath, *supportedAbis.toTypedArray())
    }
}

android {
    namespace = "lava.apiengine"

    ndkVersion = "25.1.8937393"

    defaultConfig {
        ndk {
            abiFilters.addAll(supportedAbis)
        }
        externalNativeBuild {
            cmake {
                // Tell the canonical CMakeLists where the prebuilt Go
                // c-shared .so/.h live (laid out as <ABI>/liblavaapi.{so,h}).
                arguments += "-DLAVAAPI_PREBUILT_DIR=${prebuiltJniLibsDir.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = jniCmakeLists
            version = "3.22.1"
        }
    }

    // Package the prebuilt Go c-shared library (liblavaapi.so) directly from
    // the build script's output dir. liblavaapi_jni.so is produced by the
    // externalNativeBuild above and packaged automatically.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(prebuiltJniLibsDir)
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // kotlinx.serialization.json is contributed by the lava.kotlin.serialization
    // convention plugin applied above; do not double-declare it here.

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Ensure the prebuilt Go .so/.h exist before any native build / merge task
// runs (CMake configure reads the IMPORTED .so; jniLibs merge reads the dir).
tasks.matching {
    it.name.startsWith("externalNativeBuild") ||
        it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake") ||
        it.name.startsWith("merge") && it.name.contains("JniLibFolders")
}.configureEach {
    dependsOn(buildCshared)
}
