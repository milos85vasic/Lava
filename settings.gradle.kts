@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Lava"

include(":app")
include(":core:auth:api")
include(":core:auth:impl")
include(":core:common")
include(":core:credentials")
include(":core:data")
include(":core:dispatchers")
include(":core:database")
include(":core:designsystem")
include(":core:domain")
include(":core:downloads")
include(":core:logger")
include(":core:models")
include(":core:navigation")
include(":core:network:api")
include(":core:network:impl")
include(":core:notifications")
include(":core:preferences")
include(":core:sync")
include(":core:testing")
include(":core:tracker:api")
include(":core:tracker:client")
include(":core:tracker:mirror")
include(":core:tracker:registry")
include(":core:tracker:archiveorg")
include(":core:tracker:gutenberg")
include(":core:tracker:kinozal")
include(":core:tracker:nnmclub")
include(":core:tracker:rutor")
include(":core:tracker:rutracker")
include(":core:tracker:testing")
include(":core:ui")
include(":core:work:api")
include(":core:work:impl")

include(":feature:account")
include(":feature:bookmarks")
include(":feature:category")
include(":feature:credentials")
include(":feature:credentials_manager")
include(":feature:connection")
include(":feature:favorites")
include(":feature:forum")
include(":feature:login")
include(":feature:main")
include(":feature:menu")
include(":feature:rating")
include(":feature:search")
include(":feature:search_input")
include(":feature:search_result")
include(":feature:topic")
include(":feature:tracker_settings")
include(":feature:onboarding")
include(":feature:visited")

// Tracker-SDK submodule — composite build (pinned via git submodule)
includeBuild("Submodules/Tracker-SDK") {
    dependencySubstitution {
        substitute(module("lava.sdk:api")).using(project(":api"))
        substitute(module("lava.sdk:mirror")).using(project(":mirror"))
        substitute(module("lava.sdk:registry")).using(project(":registry"))
        substitute(module("lava.sdk:testing")).using(project(":testing"))
    }
}
