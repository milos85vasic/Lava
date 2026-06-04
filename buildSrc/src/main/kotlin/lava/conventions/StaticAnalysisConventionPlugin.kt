package lava.conventions

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Static-analysis convention applied by every base module convention plugin
 * (`lava.kotlin.library`, `lava.android.library`, `lava.android.application`,
 * and transitively `lava.android.feature`). Wires three gates with zero
 * per-module configuration:
 *
 *  - Spotless + ktlint  — code style (the historically-enforced checker).
 *  - Detekt             — Kotlin static analysis. The shared rule config lives at
 *                         the repo root (`config/detekt/detekt.yml`); each module
 *                         keeps its own `detekt-baseline.xml` capturing findings
 *                         present at baseline time, so the gate is green going
 *                         forward while NEW findings fail the build. Per-module
 *                         baselines are detekt's idiomatic multi-module pattern —
 *                         a single shared baseline is incompatible because each
 *                         module's `detektBaseline` task rewrites (not merges) the
 *                         configured file.
 *  - Kover              — Kotlin line/branch coverage, collected per module.
 *
 * The shared root config is resolved against `rootProject` so every subproject
 * reuses the same rule set; baselines are per-project.
 */
internal class StaticAnalysisConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.diffplug.spotless")
                apply("io.gitlab.arturbosch.detekt")
                apply("org.jetbrains.kotlinx.kover")
            }

            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("**/*.kt")
                    targetExclude("${layout.buildDirectory}/**/*.kt")
                    ktlint("0.50.0")
                }

                kotlinGradle {
                    target("*.gradle.kts")
                    ktlint("0.50.0")
                }
            }

            val detektConfigFile = rootProject.layout.projectDirectory
                .file("config/detekt/detekt.yml").asFile
            // Per-module baseline. detekt's DetektCreateBaselineTask rewrites (does
            // not merge into) its target file, so a per-project path is the only
            // correct choice in a multi-module build.
            val detektBaselineFile = layout.projectDirectory
                .file("detekt-baseline.xml").asFile

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                parallel = true
                ignoreFailures = false
                basePath = rootProject.projectDir.path
                config.setFrom(detektConfigFile)
                baseline = detektBaselineFile
            }

            // Detekt 1.x runs its own embedded Kotlin compiler; pin its task JVM
            // target to match the project (JVM 17) and emit grep-able report
            // formats for the triage doc + the orchestrator.
            tasks.withType<Detekt>().configureEach {
                jvmTarget = "17"
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(false)
                    md.required.set(false)
                }
            }
            tasks.withType<DetektCreateBaselineTask>().configureEach {
                jvmTarget = "17"
            }
        }
    }
}
