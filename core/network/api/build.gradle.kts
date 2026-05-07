plugins {
    id("lava.kotlin.library")
    id("lava.kotlin.serialization")
}

dependencies {
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
}
