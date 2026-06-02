package lava.apiengine

import digital.vasic.lava.apigo.LavaNative
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Real [ApiEngine] backed by the embedded lava-api-go server over JNI
 * ([LavaNative]).
 *
 * Native calls run on [ioDispatcher] (default [Dispatchers.IO]) because they
 * may block on socket bind / TLS material generation / SQLite open. The native
 * Start/Stop contract is "empty string on success, error message otherwise";
 * this class maps a non-empty return to [Result.failure] and parses the Status
 * JSON into [ApiStatus].
 *
 * Requires a real Android device/emulator (the native `.so` is Android-only);
 * unit-testing this class is impossible on the JVM. Its behavior is verified by
 * the Phase E Compose UI Challenge against a real emulator, NOT by JVM tests.
 * The JVM-testable behavioral contract lives in [FakeApiEngine].
 */
class NativeApiEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = defaultJson,
) : ApiEngine {

    override suspend fun start(config: ApiConfig): Result<ApiStatus> =
        withContext(ioDispatcher) {
            runCatching {
                val configJson = json.encodeToString(config.toDto())
                val err = LavaNative.nativeStart(configJson)
                if (err.isNotEmpty()) {
                    throw ApiEngineException(err)
                }
                parseStatus(LavaNative.nativeStatus())
            }
        }

    override suspend fun stop(): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val err = LavaNative.nativeStop()
                if (err.isNotEmpty()) {
                    throw ApiEngineException(err)
                }
            }
        }

    override fun status(): ApiStatus = parseStatus(LavaNative.nativeStatus())

    private fun parseStatus(raw: String): ApiStatus {
        val dto = json.decodeFromString<StatusDto>(raw)
        return dto.toApiStatus()
    }

    private companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Thrown when a native Start/Stop call returns a non-empty error string. */
class ApiEngineException(message: String) : Exception(message)

/**
 * Wire DTO for [ApiConfig]. Field names match `internal/mobile.startConfig`'s
 * JSON tags exactly (`bindAddr`, `port`, `sqlitePath`, `authSharedKey`,
 * `authFieldName`). `authSharedKey` is emitted as `""` when null (the Go side
 * treats empty as "generate one").
 */
@Serializable
private data class ConfigDto(
    @SerialName("bindAddr") val bindAddr: String,
    @SerialName("port") val port: Int,
    @SerialName("sqlitePath") val sqlitePath: String,
    @SerialName("authSharedKey") val authSharedKey: String,
    @SerialName("authFieldName") val authFieldName: String,
)

private fun ApiConfig.toDto(): ConfigDto =
    ConfigDto(
        bindAddr = bindAddr,
        port = port,
        sqlitePath = sqlitePath,
        authSharedKey = authSharedKey.orEmpty(),
        authFieldName = authFieldName,
    )

/**
 * Wire DTO for [ApiStatus]. Field names match `internal/mobile.Status()`'s
 * JSON tags. The optional fields (`bindAddr`, `port`, `requestCount`,
 * `authFieldName`, `authKey`) are `omitempty` on the Go side when stopped, so
 * they default to stopped-state zero values here.
 */
@Serializable
private data class StatusDto(
    @SerialName("state") val state: String = "stopped",
    @SerialName("scheme") val scheme: String = "https",
    @SerialName("bindAddr") val bindAddr: String = "",
    @SerialName("port") val port: Int = 0,
    @SerialName("requestCount") val requestCount: Long = 0,
    @SerialName("backend") val backend: String = "sqlite",
    @SerialName("version") val version: String = "",
    @SerialName("authEnabled") val authEnabled: Boolean = false,
    @SerialName("authFieldName") val authFieldName: String = "",
    @SerialName("authKey") val authKey: String? = null,
)

private fun StatusDto.toApiStatus(): ApiStatus =
    ApiStatus(
        state = state,
        bindAddr = bindAddr,
        port = port,
        requestCount = requestCount,
        backend = backend,
        version = version,
        scheme = scheme,
        authEnabled = authEnabled,
        authFieldName = authFieldName,
        authKey = authKey,
    )
