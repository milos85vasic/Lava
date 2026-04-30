import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("lava.kotlin.library")
}

// Mirror :core:tracker:api's JVM 21 override so dependency resolution
// matches `lava.sdk:testing` (JVM 21 from the SDK's default toolchain).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    api(project(":core:tracker:api"))
    api("lava.sdk:testing")
    api(libs.junit4)

    testImplementation(libs.junit4)
}
