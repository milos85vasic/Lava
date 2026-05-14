package lava.domain.usecase

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Unhealthy(val status: Int) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

class ProbeMirrorUseCase @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend operator fun invoke(url: String): ProbeResult = try {
        // §6.O closure for Crashlytics 39469d3bc00aabf76a86d5d15f2e7f2b
        // ("Expected URL scheme 'http' or 'https' but no scheme was found
        // for djdnjd…", FATAL on 1.2.21 release / Galaxy S23 Ultra).
        // okhttp3.HttpUrl$Builder.parse throws IllegalArgumentException
        // for malformed URLs — was not caught by the prior IOException-only
        // catch block. The crash happened on user input "djdnjd" reaching
        // ProbeMirrorUseCase via ProviderConfigViewModel.AddMirror without
        // scheme validation. Fix: catch the broader Throwable bucket
        // (IllegalArgumentException + IOException + anything else OkHttp
        // might throw at request-build / dispatch time) and surface as
        // Unreachable with the reason. The defense-in-depth UI-side
        // validation in ProviderConfigViewModel.AddMirror prevents the
        // bad URL from being stored in the first place.
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code in 200..399) {
                ProbeResult.Reachable
            } else {
                ProbeResult.Unhealthy(resp.code)
            }
        }
    } catch (e: IllegalArgumentException) {
        // no-telemetry: user-typed mirror URL with bad scheme — the result
        // (ProbeResult.Unreachable with the parser's message) IS the
        // user-visible signal (toast in ProviderConfigViewModel.AddMirror).
        // Telemetry here would noise on every typo. Crashlytics #5 (1.2.21)
        // forensic anchor: this catch was added because okhttp threw
        // IllegalArgumentException up to the user; the catch converts to a
        // graceful fallback. No background-tracking value.
        ProbeResult.Unreachable("invalid URL: ${e.message ?: e::class.simpleName ?: "malformed"}")
    } catch (e: IOException) {
        // no-telemetry: connectivity-probe path — IOException is the
        // EXPECTED outcome when the probed mirror is offline. The result
        // value IS the user-visible signal.
        ProbeResult.Unreachable(e.message ?: e::class.simpleName ?: "io")
    }
}
