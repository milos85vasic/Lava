package lava.tracker.client

import lava.tracker.client.di.TrackerClientModule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Named

/**
 * §6.N TLS-half gap closure (bluff-hunt 2026-06-14, 1.3.8 fixes regression).
 *
 * The 2026-06-14 "search → Something went wrong" fix has TWO halves:
 *   (1) AUTH — [ApiBackedTrackerClient] attaches the per-endpoint Lava-Auth key
 *       (covered by [ApiBackedTrackerClientTest.search_attachesPerEndpointAuthKey_*]).
 *   (2) TLS  — the production DI wiring hands [ApiBackedTrackerClient] the
 *       PERMISSIVE-TLS @Named("lan") OkHttpClient, NOT the strict/system-trust
 *       unqualified client. The on-device api-app serves a self-signed cert on
 *       the LAN; the strict client SSLHandshakeException-fails the handshake →
 *       the user sees "Something went wrong" (the exact bug the fix targets — see
 *       [TrackerClientModule.provideTrackerRegistry]'s KDoc).
 *
 * Before this test, half (2) was UNTESTED: every existing test
 * ([DynamicRegistryRealClientTest], [ApiBackedTrackerClientTest]) constructs
 * [ApiBackedTrackerClient] with a plain `OkHttpClient()` of its own, and
 * [lava.network.di.LanTlsContractTest] proves the @Named("lan") client IS
 * permissive but NOT that the tracker registry is WIRED with it. A refactor
 * reverting `provideTrackerRegistry`'s parameter from `@Named("lan")` to the
 * strict unqualified `OkHttpClient` would reintroduce the production bug while
 * every test stayed green — a textbook §6.J bluff vector.
 *
 * This contract test asserts the production binding directly: the single
 * [OkHttpClient] parameter of [TrackerClientModule.provideTrackerRegistry] (the
 * one threaded into the [ApiBackedTrackerClient] factory) MUST carry
 * `@Named("lan")`. `javax.inject.Named` is `@Retention(RUNTIME)` (JSR-330), so
 * the qualifier is reflection-visible.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *   Remove `@Named("lan")` from the `lanHttpClient` parameter of
 *   `provideTrackerRegistry` (reverting to the strict client) →
 *   [registry_okHttpClient_param_is_named_lan] FAILS with
 *   "the OkHttpClient handed to the ApiBackedTrackerClient factory MUST be
 *    @Named(\"lan\") ... was: [no @Named qualifier]".
 */
class LanHttpClientWiringContractTest {

    @Test
    fun registry_okHttpClient_param_is_named_lan() {
        val method = TrackerClientModule::class.java.declaredMethods
            .single { it.name == "provideTrackerRegistry" }

        // Identify the OkHttpClient parameter(s) the factory closure consumes.
        val okHttpParamIndices = method.parameterTypes
            .mapIndexedNotNull { i, type -> i.takeIf { type == OkHttpClient::class.java } }

        assertEquals(
            "provideTrackerRegistry MUST take exactly one OkHttpClient (the one " +
                "wired into the ApiBackedTrackerClient factory)",
            1,
            okHttpParamIndices.size,
        )

        val annotations = method.parameterAnnotations[okHttpParamIndices.single()]
        val named = annotations.filterIsInstance<Named>().singleOrNull()

        val observed = named?.let { "@Named(\"${it.value}\")" } ?: "[no @Named qualifier]"
        assertNotNull(
            "the OkHttpClient handed to the ApiBackedTrackerClient factory MUST be " +
                "@Named(\"lan\") (permissive-TLS for the self-signed LAN api-go cert); " +
                "without the qualifier Hilt injects the STRICT system-trust client and " +
                "every /v1 request SSLHandshakeException-fails → user sees " +
                "\"Something went wrong\". Observed: $observed",
            named,
        )
        assertEquals(
            "the OkHttpClient qualifier MUST be exactly \"lan\" — Observed: $observed",
            "lan",
            named!!.value,
        )
    }

    /**
     * Guard the complement: the factory must NOT also be wirable with the strict
     * client by accident. A second, unqualified OkHttpClient parameter would mean
     * Hilt could resolve either — so assert there is no unqualified OkHttpClient
     * parameter alongside the @Named("lan") one.
     */
    @Test
    fun registry_has_no_unqualified_okHttpClient_param() {
        val method = TrackerClientModule::class.java.declaredMethods
            .single { it.name == "provideTrackerRegistry" }

        method.parameterTypes.forEachIndexed { i, type ->
            if (type == OkHttpClient::class.java) {
                val hasNamed = method.parameterAnnotations[i].any { it is Named }
                assertTrue(
                    "every OkHttpClient parameter of provideTrackerRegistry MUST be " +
                        "@Named-qualified; an unqualified one would let Hilt inject the " +
                        "strict system-trust client (the \"Something went wrong\" bug). " +
                        "Parameter index $i is unqualified.",
                    hasNamed,
                )
            }
        }
    }
}
