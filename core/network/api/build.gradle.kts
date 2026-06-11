plugins {
    id("lava.kotlin.library")
    id("lava.kotlin.serialization")
}

dependencies {
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
}
