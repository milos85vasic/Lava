import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("lava.kotlin.library")
    id("lava.kotlin.serialization")
}

// Tracker-SDK targets JVM 21 by default (no jvmToolchain in the SDK
// build). Override `lava.kotlin.library`'s JVM 17 target on this
// module — and the rest of the :core:tracker:* tree — so Gradle
// dependency resolution can match `lava.sdk:api`. Anything that ends
// up in the Android APK still passes through the android-library
// convention (JVM 17), so this override is local to pure-JVM
// tracker glue.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    api("lava.sdk:api")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
