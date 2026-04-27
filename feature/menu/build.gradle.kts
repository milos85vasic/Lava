plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.menu"
}

dependencies {
    implementation(project(":feature:account"))
    implementation(project(":feature:connection"))
}
