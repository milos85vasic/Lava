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
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code in 200..399) {
                ProbeResult.Reachable
            } else {
                ProbeResult.Unhealthy(resp.code)
            }
        }
    } catch (e: IOException) {
        ProbeResult.Unreachable(e.message ?: e::class.simpleName ?: "io")
    }
}
