plugins {
    id("lava.kotlin.library")
}

dependencies {
    api(project(":core:tracker:api"))
    api("lava.sdk:registry")

    testImplementation(libs.junit4)
}
