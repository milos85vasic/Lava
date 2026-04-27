plugins {
    id("lava.android.library")
    id("lava.android.hilt")
    id("lava.kotlin.ksp")
    id("androidx.room")
}

android {
    namespace = "lava.database"

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core:models"))

    implementation(libs.bundles.room)

    ksp(libs.room.compiler)
}
