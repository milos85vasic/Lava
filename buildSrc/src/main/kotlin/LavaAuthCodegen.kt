@file:Suppress("MagicNumber")

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Build-time crypto + codegen for the per-build encrypted UUID
 * embedded in the Lava Android client. Mirrors the runtime
 * [lava.network.impl.HKDF] + [lava.network.impl.AesGcm] +
 * [lava.network.impl.SigningCertProvider] math EXACTLY — any divergence
 * breaks AEAD round-trip with AEADBadTagException at request time.
 *
 * The runtime AuthInterceptor's contract:
 *   key = HKDF-SHA256(
 *       salt = SHA-256(signingCertDER)[:16],
 *       ikm  = pepper,
 *       info = "lava-auth-v1",
 *       length = 32 bytes)
 *   ciphertext = AES-256-GCM(uuidBytes, key, nonce)
 *
 * This object reproduces that pipeline. The §6.A round-trip
 * verification lives in core/network/impl/src/test/.../AuthInterceptorTest.kt
 * (the test builds a known provider with the same math and asserts the
 * runtime decrypts to the original plaintext). When this codegen
 * lands in production, an additional integration test in Phase 13's
 * Compose UI Challenge C13 closes the build-vs-runtime parity loop on
 * a real device.
 */
object LavaAuthCodegen {

    /**
     * Reads the keystore at [keystoreFile] (JKS format), extracts the
     * certificate for [keyAlias], and returns its DER-encoded bytes.
     *
     * The runtime [SigningCertProvider] reads the SAME certificate via
     * `PackageManager.getPackageInfo(... GET_SIGNING_CERTIFICATES)` —
     * the DER bytes are byte-identical, so SHA-256 over them is identical,
     * so the derived HKDF salt matches.
     */
    fun readSigningCertDer(keystoreFile: File, keystorePassword: String, keyAlias: String): ByteArray {
        val ks = KeyStore.getInstance("JKS")
        FileInputStream(keystoreFile).use { ks.load(it, keystorePassword.toCharArray()) }
        val cert = ks.getCertificate(keyAlias)
            ?: error("LavaAuthCodegen: alias \"$keyAlias\" not in $keystoreFile")
        return cert.encoded
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Mirror of runtime [lava.network.impl.HKDF.deriveKey]. */
    fun hkdfDeriveKey(salt: ByteArray, ikm: ByteArray, info: ByteArray, output: ByteArray) {
        val hmac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        hmac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        val n = (output.size + 31) / 32
        require(n in 1..255) { "HKDF: output length must be 1..255*32 bytes" }
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            hmac.update(t)
            hmac.update(info)
            hmac.update(byteArrayOf(i.toByte()))
            t = hmac.doFinal()
            val take = minOf(32, output.size - pos)
            System.arraycopy(t, 0, output, pos, take)
            pos += take
        }
    }

    /** Mirror of runtime [lava.network.impl.AesGcm.encrypt]. */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == 12) { "AES-256-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    /**
     * Parse a CSV of `name:uuid` entries from .env into a map.
     * Mirrors lava-api-go/internal/config/parseClientsList semantics
     * but without the HMAC step — at build time we need the plaintext
     * UUID for the chosen client, not its hash.
     */
    fun parseActiveClients(csv: String): Map<String, String> =
        csv.split(",").mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val colon = trimmed.indexOf(':')
            if (colon < 0) error("LavaAuthCodegen: entry \"$trimmed\" missing colon separator")
            trimmed.substring(0, colon).trim() to trimmed.substring(colon + 1).trim()
        }.toMap()

    /** Convert a 36-char hyphenated UUID string into 16 raw bytes. */
    fun parseUuid(s: String): ByteArray {
        val stripped = s.replace("-", "")
        require(stripped.length == 32) { "LavaAuthCodegen: UUID \"$s\" wrong length" }
        return ByteArray(16) { i ->
            stripped.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Top-level codegen entry. Reads .env values, derives the per-build
     * key, encrypts the UUID, writes [outputFile] containing the
     * generated `lava.auth.LavaAuthGenerated` class.
     *
     * Returns the SHA-256 of the generated source bytes — useful for
     * release-evidence files (one input, one output, deterministic per
     * keystore + .env, except for the random nonce which intentionally
     * differs every build for replay-resistance).
     */
    fun generate(
        envFile: File,
        keystoreFile: File,
        keystorePassword: String,
        keyAlias: String,
        outputFile: File,
    ): ByteArray? {
        if (!envFile.exists()) {
            // No .env — fresh checkout / CI without secrets. Skip generation;
            // runtime falls back to StubLavaAuthBlobProvider. Auth feature
            // inert until operator populates .env. Fail-closed by design.
            outputFile.delete()
            println("[LavaAuthCodegen] .env not found; skipping (auth feature inert at runtime).")
            return null
        }
        require(keystoreFile.exists()) {
            "LavaAuthCodegen: keystore not found at ${keystoreFile.absolutePath}"
        }

        val env = envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

        val currentName = env["LAVA_AUTH_CURRENT_CLIENT_NAME"]
        val activeClientsCsv = env["LAVA_AUTH_ACTIVE_CLIENTS"].orEmpty()
        val pepperB64 = env["LAVA_AUTH_OBFUSCATION_PEPPER"]
        val fieldName = env["LAVA_AUTH_FIELD_NAME"]

        if (currentName.isNullOrBlank() ||
            activeClientsCsv.isBlank() ||
            pepperB64.isNullOrBlank() ||
            fieldName.isNullOrBlank()
        ) {
            outputFile.delete()
            println(
                "[LavaAuthCodegen] .env missing one or more LAVA_AUTH_* keys " +
                    "(LAVA_AUTH_CURRENT_CLIENT_NAME, LAVA_AUTH_ACTIVE_CLIENTS, " +
                    "LAVA_AUTH_OBFUSCATION_PEPPER, LAVA_AUTH_FIELD_NAME); " +
                    "skipping (auth feature inert at runtime).",
            )
            return null
        }
        val activeClients = parseActiveClients(activeClientsCsv)
        val uuidStr = activeClients[currentName]
            ?: error("LavaAuthCodegen: \"$currentName\" not in LAVA_AUTH_ACTIVE_CLIENTS")

        val uuidBytes = parseUuid(uuidStr)
        val pepper = Base64.getDecoder().decode(pepperB64)
        val certDer = readSigningCertDer(keystoreFile, keystorePassword, keyAlias)
        val certHash = sha256(certDer)
        val salt = certHash.copyOfRange(0, 16)

        val key = ByteArray(32)
        hkdfDeriveKey(
            salt = salt,
            ikm = pepper,
            info = "lava-auth-v1".toByteArray(Charsets.UTF_8),
            output = key,
        )
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = aesGcmEncrypt(uuidBytes, key, nonce)
        // Zero plaintext + key per §6.H.
        for (i in uuidBytes.indices) uuidBytes[i] = 0
        for (i in key.indices) key[i] = 0

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            buildString {
                appendLine("// AUTO-GENERATED by LavaAuthCodegen — do not edit.")
                appendLine("// Regenerated each build from .env + signing keystore.")
                appendLine("@file:Suppress(\"MagicNumber\", \"LargeClass\")")
                appendLine("package lava.auth")
                appendLine()
                appendLine("import lava.network.impl.LavaAuthBlobProvider")
                appendLine()
                appendLine("internal class LavaAuthGenerated : LavaAuthBlobProvider {")
                appendLine("    override fun getBlob(): ByteArray = byteArrayOf(${ciphertext.toLiteral()})")
                appendLine("    override fun getNonce(): ByteArray = byteArrayOf(${nonce.toLiteral()})")
                appendLine("    override fun getPepper(): ByteArray = byteArrayOf(${pepper.toLiteral()})")
                appendLine("    override fun getFieldName(): String = \"$fieldName\"")
                appendLine("}")
            }
        )
        return sha256(outputFile.readBytes())
    }

    private fun ByteArray.toLiteral(): String =
        joinToString(", ") { "0x%02x.toByte()".format(it) }
}
