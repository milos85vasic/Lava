import lava.conventions.StaticAnalysisConventionPlugin
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Convention plugin for tracker plugin modules (e.g. :core:tracker:rutracker, :core:tracker:rutor).
 *
 * Pre-wires:
 *   - java-library + kotlin.jvm + serialization
 *   - Static analysis (Spotless / ktlint) via [StaticAnalysisConventionPlugin]
 *   - api dependency on `:core:tracker:api` (the in-repo Lava domain interfaces; created in
 *     Task 1.32 — until then this reference is a forward declaration that Gradle resolves lazily)
 *   - api dependency on `lava.sdk:api` and `lava.sdk:mirror` from the Tracker-SDK submodule.
 *     These module coordinates are substituted to the included build's projects via
 *     `dependencySubstitution` in `settings.gradle.kts` (see Task 1.31).
 *   - implementation: Jsoup, OkHttp, kotlinx-serialization-json, kotlinx-coroutines-core
 *   - testImplementation: JUnit4, kotlinx-coroutines-test, mockk, `:core:tracker:testing`
 *     and `lava.sdk:testing` (substituted to the SDK's :testing project)
 */
class KotlinTrackerModuleConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("java-library")
                apply("org.jetbrains.kotlin.jvm")
                apply("org.jetbrains.kotlin.plugin.serialization")
                apply(StaticAnalysisConventionPlugin::class.java)
            }

            tasks.withType<JavaCompile>().configureEach {
                sourceCompatibility = JavaVersion.VERSION_17.toString()
                targetCompatibility = JavaVersion.VERSION_17.toString()
            }
            tasks.withType<KotlinJvmCompile>().configureEach {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            dependencies {
                // In-repo Lava domain interfaces (Task 1.32 will create this module).
                add("api", project(":core:tracker:api"))

                // Tracker-SDK projects, addressable as external modules. The composite build
                // wired up in settings.gradle.kts substitutes these for the included build's
                // :api / :mirror / :testing projects.
                add("api", "lava.sdk:api")
                add("api", "lava.sdk:mirror")

                add("implementation", libs.findLibrary("jsoup").get())
                add("implementation", libs.findLibrary("okhttp.core").get())
                add("implementation", libs.findLibrary("kotlinx.serialization.json").get())
                add("implementation", libs.findLibrary("kotlinx.coroutines.core").get())

                add("testImplementation", libs.findLibrary("junit4").get())
                add("testImplementation", libs.findLibrary("kotlinx.coroutines.test").get())
                add("testImplementation", libs.findLibrary("mockk").get())
                add("testImplementation", project(":core:tracker:testing"))
                add("testImplementation", "lava.sdk:testing")
            }
        }
    }
}
