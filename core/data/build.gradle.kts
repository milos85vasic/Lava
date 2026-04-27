plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.data"
}

dependencies {
    implementation(project(":core:auth:api"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:dispatchers"))
    implementation(project(":core:logger"))
    implementation(project(":core:models"))
    implementation(project(":core:network:api"))
    implementation(project(":core:preferences"))
}
