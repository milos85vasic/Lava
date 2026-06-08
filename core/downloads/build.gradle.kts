plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.downloads"
}

dependencies {
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
}
