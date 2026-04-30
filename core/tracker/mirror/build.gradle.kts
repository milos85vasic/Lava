plugins {
    id("lava.kotlin.library")
    id("lava.kotlin.serialization")
}

dependencies {
    api(project(":core:tracker:api"))
    api("lava.sdk:api")
    api("lava.sdk:mirror")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation("lava.sdk:testing")
}
