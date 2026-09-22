package digital.vasic.lava.client

/**
 * releaseTest-only twin of the debug test seam (§6.AK autonomous-QA), see
 * `src/debug/.../QaKeyInjection.kt` for the full rationale. Mutable (unlike
 * `src/release/`'s immutable `val override: String? = null` no-op twin)
 * because `Challenge70AutonomousQaProviderMatrixTest` needs to assign it —
 * this build type is never distributed/shipped (see the buildTypes comment
 * in app/build.gradle.kts), so a mutable test-only hook here does not weaken
 * the real `release` artifact's "guarantees NO test hook" security posture.
 */
object QaKeyInjection {
    @Volatile var override: String? = null
}
