plugins {
    id("lava.kotlin.library")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
