plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.sync"
}

dependencies {
    api(project(":core:database"))

    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
