package lava.apiengine

import digital.vasic.lava.apigo.LavaNative
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * §6.A real-binary contract test for the `:core:apiengine` JNI/native bridge.
 *
 * This module ships two surfaces that the on-device `:api-app` depends on but
 * that NO existing JVM test guards:
 *
 *  1. The **JNI symbol-name contract** between [LavaNative] (Kotlin glue) and
 *     `lava-api-go/cmd/lavaapi-cshared/jni/jni_bridge.c` (a native binary WE
 *     own/build). The C bridge exports
 *     `Java_digital_vasic_lava_apigo_LavaNative_nativeStart` (and …Stop/…Status)
 *     — symbol names DERIVED from the Kotlin object's fully-qualified name and
 *     its `external` method names per the JNI mangling rule
 *     `Java_<pkg-dots→underscores>_<ClassName>_<methodName>`. If anyone renames
 *     the package, the object, or a method, the derived symbol no longer matches
 *     the C export and the app dies with `UnsatisfiedLinkError` on the FIRST
 *     native call on a real device — exactly the DT_SONAME-class linkage defect
 *     CONTINUATION records this layer hiding. This is the §6.A pattern: recover
 *     the Kotlin glue's actual identity by reflection, derive the JNI symbol it
 *     produces, and assert it is the EXACT symbol the C bridge expects. No `.so`,
 *     no device required — the linkage contract is checkable at the JVM level
 *     because it is a pure function of names on both sides.
 *
 *  2. The **config wire byte/field-level contract** between [ConfigDto]
 *     (serialized on every `start()`) and the Go `internal/mobile.startConfig`
 *     struct. [ConfigSerializationTest] asserts each key is *present* via
 *     `String.contains`, which a renamed/extra/reordered field can still pass
 *     (e.g. a stray `"bind_addr"` alongside `"bindAddr"` would not fail a
 *     contains-check). This test asserts the EXACT field-name SET (no extra, no
 *     missing, no renamed) AND the EXACT field ORDER, matching the Go struct's
 *     JSON-tag declaration order — the byte-level wire shape the native side
 *     unmarshals. The serialization defect this layer hid (Json serializer not
 *     generated when the compiler plugin was absent) is regression-guarded by
 *     [ConfigSerializationTest]; this test guards the COMPLEMENTARY defect class
 *     (a field drifting name/order/membership while still "containing" the old
 *     substrings).
 *
 * What this contract test CANNOT verify (honest device-only gaps, NOT faked):
 *  - That `liblavaapi_jni.so` actually EXPORTS those symbols (needs the built
 *    `.so` + a loader; `System.loadLibrary` is Android-only). Covered by the
 *    Phase E on-device Challenge, not here.
 *  - That `nativeStart`/`nativeStop`/`nativeStatus` return the documented
 *    "" / error-string / JSON values at runtime. Needs the native lib loaded.
 *  - The DT_SONAME of the built `.so` itself (an ELF-level property of the
 *    compiled artifact, not derivable from Kotlin source). The C-bridge symbol
 *    contract checked here is the SOURCE-side half of that linkage; the ELF half
 *    is a build/on-device gap.
 */
class NativeApiEngineContractTest {

    // ── §6.A surface 1: JNI symbol-name contract ───────────────────────────

    /**
     * The literal JNI symbols the C bridge exports. These are copied verbatim
     * from `jni_bridge.c`'s `JNIEXPORT jstring JNICALL Java_…` definitions.
     * They are the source-of-truth the Kotlin glue MUST mangle-to.
     *
     * If `jni_bridge.c` ever changes these (e.g. a package/object rename in
     * lockstep with the Kotlin side), this map is updated in the SAME commit —
     * the test then re-proves the two sides agree.
     */
    private val expectedJniSymbols = mapOf(
        "nativeStart" to "Java_digital_vasic_lava_apigo_LavaNative_nativeStart",
        "nativeStop" to "Java_digital_vasic_lava_apigo_LavaNative_nativeStop",
        "nativeStatus" to "Java_digital_vasic_lava_apigo_LavaNative_nativeStatus",
    )

    /**
     * Derives the JNI symbol name for a native method on [clazz], per the JNI
     * short-name mangling rule (sufficient here because none of the names
     * contain `_`, `;`, `[`, or non-ASCII chars that would trigger escaping).
     */
    private fun jniSymbolFor(clazz: Class<*>, methodName: String): String =
        "Java_" + clazz.name.replace('.', '_') + "_" + methodName

    @Test
    fun lavaNative_jniSymbols_matchTheCBridgeExports() {
        val clazz = LavaNative::class.java

        // Primary assertion: the Kotlin glue's ACTUAL fully-qualified name +
        // method names mangle to EXACTLY the symbols the C bridge exports. A
        // rename on either side (the DT_SONAME/linkage defect class) fails here.
        expectedJniSymbols.forEach { (method, expectedSymbol) ->
            val derived = jniSymbolFor(clazz, method)
            assertEquals(
                "JNI symbol for $method drifted from the C bridge export — on-device " +
                    "this is an UnsatisfiedLinkError on the first native call",
                expectedSymbol,
                derived,
            )
        }

        // The fully-qualified name itself is load-bearing for the symbol; pin it
        // so a package/object move is an explicit, reviewed change.
        assertEquals(
            "digital.vasic.lava.apigo.LavaNative",
            clazz.name,
        )
    }

    @Test
    fun lavaNative_declaresExactlyTheNativeMethodsTheBridgeImplements() {
        val clazz = LavaNative::class.java

        // Every method the C bridge implements MUST exist on the Kotlin object
        // and MUST be declared `native` (Kotlin `external`). A method that is
        // present but not native would be a JVM method the JNI never reaches —
        // a silent contract break.
        expectedJniSymbols.keys.forEach { methodName ->
            val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
            assertTrue(
                "LavaNative.$methodName is missing — the C bridge symbol " +
                    "${expectedJniSymbols[methodName]} would have nothing to bind to",
                method != null,
            )
            assertTrue(
                "LavaNative.$methodName must be `external` (JVM native); the C " +
                    "bridge can only bind a native method",
                Modifier.isNative(method!!.modifiers),
            )
        }

        // And the reverse: no EXTRA native method exists on LavaNative that the
        // C bridge does NOT implement (which would UnsatisfiedLinkError on call).
        val actualNativeMethods =
            clazz.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.toSet()
        assertEquals(
            "LavaNative declares native methods with no matching C-bridge symbol — " +
                "calling one is an on-device UnsatisfiedLinkError",
            expectedJniSymbols.keys,
            actualNativeMethods,
        )
    }

    @Test
    fun nativeStart_signature_matchesTheBridge_stringArgStringReturn() {
        // jni_bridge.c: nativeStart(jstring configJson) -> jstring.
        val start = LavaNative::class.java.declaredMethods.first { it.name == "nativeStart" }
        assertEquals(
            "nativeStart must take exactly one String arg (the config JSON the " +
                "bridge passes to LavaApiStart)",
            listOf<Class<*>>(String::class.java),
            start.parameterTypes.toList(),
        )
        assertEquals(
            "nativeStart must return String (the bridge returns NewStringUTF)",
            String::class.java,
            start.returnType,
        )

        // nativeStop / nativeStatus take no args and return String.
        listOf("nativeStop", "nativeStatus").forEach { name ->
            val m = LavaNative::class.java.declaredMethods.first { it.name == name }
            assertEquals("$name must take no args", 0, m.parameterTypes.size)
            assertEquals("$name must return String", String::class.java, m.returnType)
        }
    }

    // ── §6.A surface 2: config wire byte/field-level contract ──────────────

    private val json = NativeApiEngine.defaultJson

    /**
     * The EXACT JSON keys, in declaration order, of the Go
     * `internal/mobile.startConfig` struct (its `json:"…"` tags, top→bottom).
     * This is the wire shape the native side unmarshals. Copied verbatim from
     * `mobile.go`; updated in lockstep if the Go struct changes.
     */
    private val goStartConfigKeysInOrder =
        listOf("bindAddr", "port", "sqlitePath", "authSharedKey", "authFieldName")

    @Test
    fun configDto_wireKeySet_isExactlyTheGoStartConfigKeys() {
        val config =
            ApiConfig(
                bindAddr = "10.0.0.5",
                port = 9443,
                sqlitePath = "/data/data/app/files/lava.db",
                authSharedKey = "base64-uuid-blob",
                authFieldName = "Lava-Auth",
            )

        val wire = json.encodeToString(config.toDto())
        val actualKeys = topLevelJsonKeysInOrder(wire)

        // Primary assertion on the on-the-wire bytes: the SET of emitted keys is
        // EXACTLY the Go struct's keys — no missing key (Go would default it to
        // the zero value, silently mis-binding), no extra/renamed key (Go's
        // default decoder ignores unknowns, so a typo'd `"bind_addr"` would be
        // dropped while `bindAddr` stays zero — a contains-check cannot catch it).
        assertEquals(
            "ConfigDto wire keys drifted from internal/mobile.startConfig — " +
                "wire was: $wire",
            goStartConfigKeysInOrder.toSet(),
            actualKeys.toSet(),
        )
    }

    @Test
    fun configDto_wireKeyOrder_matchesGoStartConfigDeclarationOrder() {
        val config =
            ApiConfig(
                bindAddr = "0.0.0.0",
                port = 8443,
                sqlitePath = "/tmp/lava.db",
                authSharedKey = "k",
                authFieldName = "Lava-Auth",
            )

        val wire = json.encodeToString(config.toDto())

        // The field order must match the Go struct's tag-declaration order. This
        // is byte-level on the wire (kotlinx-serialization emits in @SerialName
        // declaration order). Order drift is harmless to Go's decoder but is a
        // canary that the DTO was edited away from a faithful mirror of the
        // struct — the kind of silent shape drift this layer has hidden before.
        assertEquals(
            "ConfigDto field ORDER drifted from internal/mobile.startConfig " +
                "declaration order — wire was: $wire",
            goStartConfigKeysInOrder,
            topLevelJsonKeysInOrder(wire),
        )
    }

    @Test
    fun configDto_fullRoundTrip_isByteIdenticalForKnownConfig() {
        // A fixed config produces a byte-exact wire string. This is the strongest
        // possible assertion on the serialized shape: any field rename, reorder,
        // type change, default-encoding change, or escaping change flips the
        // exact bytes the native side receives.
        val config =
            ApiConfig(
                bindAddr = "0.0.0.0",
                port = 8443,
                sqlitePath = "/data/data/app/files/lava.db",
                authSharedKey = "dGVzdC1rZXk=",
                authFieldName = "Lava-Auth",
            )

        val wire = json.encodeToString(config.toDto())

        assertEquals(
            "{\"bindAddr\":\"0.0.0.0\"," +
                "\"port\":8443," +
                "\"sqlitePath\":\"/data/data/app/files/lava.db\"," +
                "\"authSharedKey\":\"dGVzdC1rZXk=\"," +
                "\"authFieldName\":\"Lava-Auth\"}",
            wire,
        )
    }

    /**
     * Parses the top-level object's keys in their emitted order from a flat JSON
     * object string. Sufficient because [ConfigDto] is a flat object of scalar
     * values (no nested objects/arrays), so a depth-0 key scan is exact. Avoids
     * pulling in a JSON-tree dependency the module does not already have.
     */
    private fun topLevelJsonKeysInOrder(jsonObject: String): List<String> {
        val keys = mutableListOf<String>()
        var i = 0
        var depth = 0
        var inString = false
        var escaped = false
        val s = jsonObject
        var pendingKeyStart = -1
        while (i < s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> {
                    if (!inString) {
                        pendingKeyStart = i + 1
                        inString = true
                    } else {
                        inString = false
                        // A string closing at depth 1 that is immediately
                        // followed (after optional whitespace) by ':' is an
                        // object key.
                        val key = s.substring(pendingKeyStart, i)
                        var j = i + 1
                        while (j < s.length && s[j].isWhitespace()) j++
                        if (depth == 1 && j < s.length && s[j] == ':') {
                            keys.add(key)
                        }
                    }
                }
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
            i++
        }
        return keys
    }
}
