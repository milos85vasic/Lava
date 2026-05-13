package lava.network.data

import lava.logger.api.Logger
import lava.logger.api.LoggerFactory
import lava.network.impl.LavaAuthBlobProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5 follow-up (2026-05-13) — NetworkLogger MUST redact the
 * configured Lava-Auth header VALUE before forwarding lines to the
 * underlying logger. Constitutional clause in `core/CLAUDE.md`:
 *
 *   > the Base64-encoded header VALUE is a `String` (immutable) but
 *   > never logged, never persisted, never assigned to a class field…
 *
 * §6.J primary: the captured log line at the LoggerFactory boundary.
 * If the value survives the redactor, this test fails and operator
 * sees the leak in the test report — same surface as if a real
 * `LogLevel.ALL` run had leaked.
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit):
 *
 *   1. In [NetworkLogger.redactAuthHeader], replace the `replace(...)`
 *      call with `message` (no-op).
 *   2. Run this test.
 *   3. Expected failure: assertFalse on "must NOT contain the raw
 *      auth value" fires — the unredacted value appears in the
 *      captured line.
 *   4. Revert; re-run; green.
 */
class NetworkLoggerRedactionTest {

    private class RecordingLogger : Logger {
        val lines = mutableListOf<String>()
        override fun i(message: () -> String) { lines.add(message()) }
        override fun d(message: () -> String) { lines.add(message()) }
        override fun d(t: Throwable?, message: () -> String) { lines.add(message()) }
        override fun e(message: () -> String) { lines.add(message()) }
        override fun e(t: Throwable?, message: () -> String) { lines.add(message()) }
    }

    private class RecordingLoggerFactory(val tagged: RecordingLogger) : LoggerFactory {
        override fun get(tag: String): Logger = tagged
    }

    private class FixedFieldNameProvider(private val fieldName: String) : LavaAuthBlobProvider {
        override fun getBlob(): ByteArray = ByteArray(0)
        override fun getNonce(): ByteArray = ByteArray(0)
        override fun getPepper(): ByteArray = ByteArray(0)
        override fun getFieldName(): String = fieldName
    }

    @Test
    fun `redacts the configured auth header value when present`() {
        val sink = RecordingLogger()
        val factory = RecordingLoggerFactory(sink)
        val provider = FixedFieldNameProvider("Lava-Auth")
        val networkLogger = NetworkLogger(factory, provider)

        val rawHeaderValue = "kQHRzU7M2YwYjQwM2VmNTRkLW5vbi1zZWNyZXQtdGVzdC1maXh0dXJl"
        val message = """
            REQUEST: https://rutracker.org/forum/index.php
            METHOD: GET
            Lava-Auth: $rawHeaderValue
            User-Agent: Mozilla/5.0
        """.trimIndent()

        networkLogger.log(message)

        assertEquals("logger must emit exactly one line", 1, sink.lines.size)
        val captured = sink.lines.single()

        // §6.J primary — wire-observable signal.
        assertFalse(
            "captured log line must NOT contain the raw auth value; was: $captured",
            captured.contains(rawHeaderValue),
        )
        // Secondary — the redaction marker is present.
        assertTrue(
            "captured log line must contain '<redacted>' substitution; was: $captured",
            captured.contains("<redacted>"),
        )
        // Secondary — non-auth lines are preserved verbatim.
        assertTrue(
            "non-auth lines must survive the redactor; was: $captured",
            captured.contains("User-Agent: Mozilla/5.0"),
        )
    }

    @Test
    fun `redaction is no-op when no auth field name is configured (stub provider)`() {
        val sink = RecordingLogger()
        val factory = RecordingLoggerFactory(sink)
        val provider = FixedFieldNameProvider("") // matches StubLavaAuthBlobProvider
        val networkLogger = NetworkLogger(factory, provider)

        val message = "REQUEST: https://rutracker.org/\nMETHOD: GET"
        networkLogger.log(message)

        assertEquals(message, sink.lines.single())
    }

    @Test
    fun `redaction is case-insensitive on the header name`() {
        val sink = RecordingLogger()
        val provider = FixedFieldNameProvider("Lava-Auth")
        val networkLogger = NetworkLogger(RecordingLoggerFactory(sink), provider)

        val rawValue = "abcdef123456"
        networkLogger.log("lava-auth: $rawValue")

        val captured = sink.lines.single()
        assertFalse("case-insensitive match must still redact", captured.contains(rawValue))
        assertTrue(captured.contains("<redacted>"))
    }
}
