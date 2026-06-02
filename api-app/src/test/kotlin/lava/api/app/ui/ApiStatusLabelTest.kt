package lava.api.app.ui

import lava.api.app.control.ApiControlState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the user-facing status labels + per-state button enablement.
 *
 * §6.AB rendering-correctness: NO status label may contain an underscore (the
 * 60th-§6.L underscore-in-subtitle class). [ApiStatusLabels.statusText] derives
 * labels deliberately per state, so this test fails if anyone wires it to
 * `state::class.simpleName` of a future underscore-bearing state name.
 *
 * Bluff-Audit (recorded in commit body):
 *   - statusText: returning `state.javaClass.simpleName` for Running keeps the
 *     no-underscore test green by luck, BUT changing startEnabled() to
 *     `state is ApiControlState.Running` makes `Start enabled only when stopped
 *     or error` fail with "Start must be DISABLED while Running".
 */
class ApiStatusLabelTest {

    private val allStates = listOf(
        ApiControlState.Stopped,
        ApiControlState.Starting,
        ApiControlState.Running(
            url = "https://192.168.1.42:8443",
            lanIps = listOf("192.168.1.42"),
            port = 8443,
            requestCount = 3,
            authKey = "dGVzdC1rZXk=",
            authFieldName = "Lava-Auth",
        ),
        ApiControlState.Stopping,
        ApiControlState.Error("boom"),
    )

    @Test
    fun `no status label contains an underscore`() {
        allStates.forEach { state ->
            val label = ApiStatusLabels.statusText(state)
            assertFalse(
                "status label for $state must not contain '_': '$label'",
                label.contains('_'),
            )
        }
    }

    @Test
    fun `status labels are the expected human words`() {
        assertEquals("Stopped", ApiStatusLabels.statusText(ApiControlState.Stopped))
        assertEquals("Starting…", ApiStatusLabels.statusText(ApiControlState.Starting))
        assertEquals("Stopping…", ApiStatusLabels.statusText(ApiControlState.Stopping))
        assertEquals("Error", ApiStatusLabels.statusText(ApiControlState.Error("x")))
        assertEquals(
            "Running",
            ApiStatusLabels.statusText(
                ApiControlState.Running("u", emptyList(), 1, 0, "k", "Lava-Auth"),
            ),
        )
    }

    @Test
    fun `Start enabled only when stopped or error`() {
        assertTrue("Start must be enabled while Stopped", ApiStatusLabels.startEnabled(ApiControlState.Stopped))
        assertTrue("Start must be enabled on Error", ApiStatusLabels.startEnabled(ApiControlState.Error("x")))
        assertFalse("Start must be DISABLED while Starting", ApiStatusLabels.startEnabled(ApiControlState.Starting))
        assertFalse("Start must be DISABLED while Stopping", ApiStatusLabels.startEnabled(ApiControlState.Stopping))
        assertFalse(
            "Start must be DISABLED while Running",
            ApiStatusLabels.startEnabled(
                ApiControlState.Running("u", emptyList(), 1, 0, "k", "Lava-Auth"),
            ),
        )
    }

    @Test
    fun `Stop and Restart enabled only when running`() {
        val running = ApiControlState.Running("u", emptyList(), 1, 0, "k", "Lava-Auth")
        assertTrue(ApiStatusLabels.stopEnabled(running))
        assertTrue(ApiStatusLabels.restartEnabled(running))
        listOf(
            ApiControlState.Stopped,
            ApiControlState.Starting,
            ApiControlState.Stopping,
            ApiControlState.Error("x"),
        ).forEach { state ->
            assertFalse("Stop must be DISABLED while $state", ApiStatusLabels.stopEnabled(state))
            assertFalse("Restart must be DISABLED while $state", ApiStatusLabels.restartEnabled(state))
        }
    }
}
