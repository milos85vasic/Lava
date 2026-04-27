plugins {
    id("lava.kotlin.library")
}

dependencies {
    implementation(project(":core:models"))
    implementation(libs.kotlinx.coroutines.core)
}
