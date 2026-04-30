plugins {
    id("lava.kotlin.library")
}

dependencies {
    api(project(":core:tracker:api"))
    api("lava.sdk:testing")
    api(libs.junit4)

    testImplementation(libs.junit4)
}
