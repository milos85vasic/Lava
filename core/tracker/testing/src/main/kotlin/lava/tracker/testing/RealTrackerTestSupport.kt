package lava.tracker.testing

import lava.common.torrent.DownloadValidationResult
import lava.common.torrent.MagnetLinkValidator
import lava.common.torrent.TorrentFileValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Shared support for the CROWN-JEWEL real-network verification suite — the
 * per-provider integration tests that replace manual human testing of "does a
 * download option actually work" against the four real Russian trackers
 * (rutracker, rutor, kinozal, nnmclub).
 *
 * ## Anti-Bluff posture (constitutional §6.J / §6.L / Seventh Law)
 *
 * These trackers may be unreachable from a given host (Cloudflare challenge,
 * geo-block, ISP block, or transient network failure). A test built on this
 * support MUST `assumeTrue`-SKIP-with-reason on any of:
 *   - the `-PrealTrackers=true` gate not being set ([realTrackersEnabled]),
 *   - missing credentials in the environment ([credentials]),
 *   - the tracker being unreachable ([RealTrackerHarness.assumeReachable]),
 *   - real login failing ([RealTrackerHarness.assumeAuthenticated]).
 *
 * It MUST NEVER produce a fake PASS. A reachable run downloads a REAL `.torrent`
 * and validates it (non-empty + valid bencode + computed info-hash) and/or
 * extracts the magnet and validates the btih; an unreachable run honestly SKIPs.
 *
 * ## §6.R no-hardcoding
 *
 * Credentials are NEVER hardcoded. They are read at test runtime from the
 * environment (populated by the gitignored `.env`): `<PREFIX>_USERNAME` /
 * `<PREFIX>_PASSWORD`. No host literal that identifies a real account is baked
 * into source; the tracker hostnames themselves come from each provider's
 * `TrackerDescriptor.baseUrls`.
 */
object RealTrackerTestSupport {

    /**
     * The `-PrealTrackers=true` gate, observed by the test JVM either as the
     * `realTrackers` system property (forwarded by each module's `test` task
     * `systemProperty("realTrackers", ...)`) or the `LAVA_REAL_TRACKERS`
     * environment variable. Default OFF: the suite is gated off unless the
     * operator explicitly opts in, so a plain `./gradlew test` never makes
     * outbound calls.
     */
    fun realTrackersEnabled(): Boolean {
        val prop = System.getProperty("realTrackers")?.trim()?.lowercase()
        if (prop == "true" || prop == "1") return true
        val env = System.getenv("LAVA_REAL_TRACKERS")?.trim()?.lowercase()
        return env == "true" || env == "1"
    }

    /**
     * Reads `<prefix>_USERNAME` / `<prefix>_PASSWORD` from the environment.
     * Returns `null` when either is absent or blank — the caller then SKIPs.
     * Never logs the password.
     */
    fun credentials(prefix: String): RealCredentials? {
        val user = System.getenv("${prefix}_USERNAME")?.trim().orEmpty()
        val pass = System.getenv("${prefix}_PASSWORD")?.trim().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) return null
        return RealCredentials(user, pass)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/** Real tracker credentials. [toString] redacts the password per §6.H. */
data class RealCredentials(val username: String, val password: String) {
    override fun toString(): String = "RealCredentials(username=$username, password=<redacted>)"
}

/**
 * Per-provider harness: builds the reachability probe + evidence writer around a
 * single tracker. Construct one with the provider's id and primary base URL,
 * then drive the real client through it.
 */
class RealTrackerHarness(
    val providerId: String,
    val baseUrl: String,
) {
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val torrentValidator = TorrentFileValidator()
    private val magnetValidator = MagnetLinkValidator()

    /**
     * Probes the tracker's root over the real network. Returns a [ReachResult]
     * describing whether the host answered with a usable HTTP response. NEVER
     * throws — a network failure is captured as `reachable = false` with the
     * exception message, so the caller turns it into an honest SKIP, not a fail.
     */
    fun probe(): ReachResult = try {
        val request = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        probeClient.newCall(request).execute().use { response ->
            ReachResult(
                reachable = response.code in 200..399,
                httpCode = response.code,
                detail = "HTTP ${response.code} from $baseUrl",
            )
        }
    } catch (t: Throwable) {
        ReachResult(
            reachable = false,
            httpCode = null,
            detail = "unreachable: ${t.javaClass.simpleName}: ${t.message}",
        )
    }

    fun validateTorrent(bytes: ByteArray): DownloadValidationResult =
        torrentValidator.validate(bytes)

    fun validateMagnet(magnet: String): DownloadValidationResult =
        magnetValidator.validate(magnet)

    /**
     * Writes a real-evidence JSON record under
     * `.lava-ci-evidence/realtracker/<provider>-<date>.json`. Called ONLY when a
     * run actually reached the network, so the file's mere existence is proof a
     * real outbound run happened. Password is never written. Returns the file.
     *
     * Walks up from the working directory to find the repo root (the directory
     * containing `.lava-ci-evidence` or `settings.gradle.kts`) so the path is
     * stable regardless of which module's test JVM is forked.
     */
    fun writeEvidence(evidence: RealTrackerEvidence): File {
        val dir = File(repoRoot(), ".lava-ci-evidence/realtracker")
        dir.mkdirs()
        val date = java.time.LocalDate.now().toString()
        val file = File(dir, "$providerId-$date.json")
        file.writeText(evidence.toJson())
        return file
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, ".lava-ci-evidence").isDirectory ||
                File(dir, "settings.gradle.kts").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").absoluteFile
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 12L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Result of a reachability probe. Never an exception — an honest data record. */
data class ReachResult(
    val reachable: Boolean,
    val httpCode: Int?,
    val detail: String,
)

/**
 * Real-download evidence captured when a run reaches the network. Serialized
 * with a tiny hand-rolled JSON writer to avoid pulling a serialization runtime
 * into this test-support module. NEVER includes credentials.
 */
data class RealTrackerEvidence(
    val provider: String,
    val baseUrl: String,
    val timestampUtc: String,
    val username: String,
    val authenticated: Boolean,
    val searchQuery: String,
    val searchResultCount: Int,
    val topicId: String?,
    val torrentByteLength: Int?,
    val torrentSha256: String?,
    val torrentValid: Boolean?,
    val torrentInfoHash: String?,
    val torrentValidatorReason: String?,
    val magnetUri: String?,
    val magnetValid: Boolean?,
    val magnetInfoHash: String?,
    val magnetValidatorReason: String?,
    val infoHashCrossCheckMatch: Boolean?,
    val notes: String,
) {
    fun toJson(): String {
        fun esc(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        fun str(v: String?): String = if (v == null) "null" else "\"${esc(v)}\""
        fun bool(v: Boolean?): String = v?.toString() ?: "null"
        fun num(v: Int?): String = v?.toString() ?: "null"
        return buildString {
            append("{\n")
            append("  \"provider\": ${str(provider)},\n")
            append("  \"baseUrl\": ${str(baseUrl)},\n")
            append("  \"timestampUtc\": ${str(timestampUtc)},\n")
            append("  \"username\": ${str(username)},\n")
            append("  \"authenticated\": ${bool(authenticated)},\n")
            append("  \"searchQuery\": ${str(searchQuery)},\n")
            append("  \"searchResultCount\": ${num(searchResultCount)},\n")
            append("  \"topicId\": ${str(topicId)},\n")
            append("  \"torrentByteLength\": ${num(torrentByteLength)},\n")
            append("  \"torrentSha256\": ${str(torrentSha256)},\n")
            append("  \"torrentValid\": ${bool(torrentValid)},\n")
            append("  \"torrentInfoHash\": ${str(torrentInfoHash)},\n")
            append("  \"torrentValidatorReason\": ${str(torrentValidatorReason)},\n")
            append("  \"magnetUri\": ${str(magnetUri)},\n")
            append("  \"magnetValid\": ${bool(magnetValid)},\n")
            append("  \"magnetInfoHash\": ${str(magnetInfoHash)},\n")
            append("  \"magnetValidatorReason\": ${str(magnetValidatorReason)},\n")
            append("  \"infoHashCrossCheckMatch\": ${bool(infoHashCrossCheckMatch)},\n")
            append("  \"notes\": ${str(notes)}\n")
            append("}\n")
        }
    }
}
