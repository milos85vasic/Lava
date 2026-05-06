package lava.network.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the SHA-256 of the running APK's signing-certificate DER bytes.
 *
 * Used by [AuthInterceptor] (Phase 10) as the HKDF salt half — bound
 * to the signing identity so a re-signed APK breaks the derived key
 * (AEAD decrypt fails closed). The build-time codegen reads the same
 * certificate from the keystore file; identical DER bytes → identical
 * hash → identical derived key → AES-GCM decrypt succeeds at runtime.
 */
@Singleton
internal class SigningCertProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun sha256(): ByteArray {
        val pm = context.packageManager
        val pkgName = context.packageName
        val derBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = info.signingInfo
                ?: error("SigningCertProvider: signingInfo is null")
            val signers = if (signatures.hasMultipleSigners()) {
                signatures.apkContentsSigners
            } else {
                signatures.signingCertificateHistory
            }
            require(signers.isNotEmpty()) { "SigningCertProvider: no signing certificate" }
            signers[0].toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = info.signatures
                ?: error("SigningCertProvider: signatures null on legacy API")
            require(sigs.isNotEmpty()) { "SigningCertProvider: no signing certificate" }
            sigs[0].toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(derBytes)
    }
}
