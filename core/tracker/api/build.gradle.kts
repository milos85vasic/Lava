plugins {
    id("lava.kotlin.library")
    id("lava.kotlin.serialization")
}

dependencies {
    api("lava.sdk:api")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
