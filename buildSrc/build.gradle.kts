plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.firebase.crashlytics.gradlePlugin)
    implementation(libs.google.services.gradlePlugin)
    implementation(libs.kotlin.composePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationPlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.ktor.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.room.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "lava.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "lava.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "lava.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "lava.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "lava.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("kotlinKsp") {
            id = "lava.kotlin.ksp"
            implementationClass = "KotlinKspConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "lava.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "lava.kotlin.serialization"
            implementationClass = "KotlinSerializationConventionPlugin"
        }
        register("ktorApplication") {
            id = "lava.ktor.application"
            implementationClass = "KtorApplicationConventionPlugin"
        }
    }
}
