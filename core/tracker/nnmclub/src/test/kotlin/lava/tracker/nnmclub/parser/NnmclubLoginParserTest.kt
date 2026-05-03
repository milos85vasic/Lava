package lava.tracker.nnmclub.parser

import lava.tracker.api.model.AuthState
import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertTrue
import org.junit.Test

class NnmclubLoginParserTest {

    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubLoginParser()

    @Test
    fun `parse success page returns Authenticated`() {
        val html = loader.load("login", "login-success-2026-05-02.html")
        val result = parser.parse(html)

        assertTrue("expected Authenticated", result.state is AuthState.Authenticated)
    }

    @Test
    fun `parse failure page returns Unauthenticated`() {
        val html = loader.load("login", "login-failure-2026-05-02.html")
        val result = parser.parse(html)

        assertTrue("expected Unauthenticated", result.state is AuthState.Unauthenticated)
    }
}
