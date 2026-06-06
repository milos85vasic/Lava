plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}

android {
    namespace = "lava.favorites"
}

dependencies {
    // FavoritesViewModelTest wires the REAL SyncFavoritesUseCase, whose
    // constructor needs the NotificationService boundary type. core:domain
    // depends on core:notifications via `implementation`, so it is not on
    // this module's test classpath transitively — add it for tests only.
    testImplementation(project(":core:notifications"))
}
