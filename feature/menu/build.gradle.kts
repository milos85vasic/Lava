plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.menu"
}

dependencies {
    implementation(project(":core:credentials"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:tracker:api"))
    implementation(project(":core:tracker:client"))
    implementation(project(":feature:account"))
    implementation(project(":feature:connection"))
}
