import org.gradle.api.Plugin
import org.gradle.api.Project

class KtorApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("lava.kotlin.library")
                apply("lava.kotlin.serialization")
                apply("org.gradle.application")
                apply("io.ktor.plugin")
            }
        }
    }
}
