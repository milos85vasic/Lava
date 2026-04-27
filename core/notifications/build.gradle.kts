plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.notifications"
}

dependencies {
    implementation(project(":core:models"))
    implementation(project(":core:designsystem"))
}
