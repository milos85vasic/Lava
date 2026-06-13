plugins {
    id("lava.android.library")
    id("lava.android.hilt")
}

android {
    namespace = "lava.downloads"

    testOptions {
        // DownloadServiceImpl.downloadHttpFile touches android.os.Build /
        // MediaStore / Environment statics. On the plain JVM those Android stubs
        // throw "not mocked" RuntimeExceptions unless default-values are
        // returned. The cancellation-rethrow test below drives the real
        // production catch and only needs the resolver boundary controlled.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
