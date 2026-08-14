package lava.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import lava.logger.api.LoggerFactory
import lava.network.impl.AuthInterceptor
import lava.network.impl.LavaAuthBlobProvider
import lava.network.impl.SigningCertProvider
import lava.network.impl.StubLavaAuthBlobProvider
import okhttp3.Interceptor
import javax.inject.Singleton

/**
 * Wires [AuthInterceptor] into the OkHttp interceptor multibind set.
 *
 * The blob-provider binding is dynamic: at boot we look up
 * `lava.auth.LavaAuthGenerated` via reflection. If present (Phase 11
 * has generated it), use it. Otherwise fall back to the stub
 * (Phase 10 default — auth feature inert).
 *
 * This pattern avoids the Hilt-multiple-bindings problem: a single
 * `@Provides` method decides the implementation. Phase 11 just adds
 * the class file, no Hilt-module edit required.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AuthInterceptorModule {

    @Provides
    @Singleton
    fun provideLavaAuthBlobProvider(stub: StubLavaAuthBlobProvider): LavaAuthBlobProvider =
        tryLoadGenerated() ?: stub

    @Provides
    @Singleton
    @IntoSet
    fun provideAuthInterceptor(
        blobProvider: LavaAuthBlobProvider,
        signingCertProvider: SigningCertProvider,
        loggerFactory: LoggerFactory,
    ): Interceptor = AuthInterceptor(
        blobProvider = blobProvider,
        signingCertHash = AuthInterceptor.SigningCertHash { signingCertProvider.sha256() },
        loggerFactory = loggerFactory,
    )

    /**
     * Returns the Phase-11-generated [LavaAuthBlobProvider] if its
     * class is on the classpath; null otherwise. Reflection is used
     * once at boot (singleton) so the per-request hot path is
     * unaffected.
     */
    private fun tryLoadGenerated(): LavaAuthBlobProvider? {
        // LVA-098 (2026-08-14, §6.AC): this fallback used to be completely
        // silent — a ClassNotFoundException here means every request goes
        // out with NO Lava-Auth header at all (StubLavaAuthBlobProvider's
        // getBlob() returns empty), and nothing anywhere logged WHY. That
        // silence is what let the real LVA-098 bug (app/build.gradle.kts's
        // generated-source directory never reaching compileDebugKotlin, so
        // lava.auth.LavaAuthGenerated compiled to disk but never into the
        // APK's dex) go undetected on every real device. Using
        // android.util.Log directly (not the DI'd LoggerFactory, which
        // isn't available yet at this static/early binding-decision point).
        // Kept permanently, not a temp diagnostic — one log line at boot.
        return try {
            val cls = Class.forName("lava.auth.LavaAuthGenerated")
            val instance = cls.getDeclaredConstructor().newInstance() as? LavaAuthBlobProvider
            android.util.Log.i("AuthInterceptorModule", "[authblob-diag] LavaAuthGenerated FOUND, instance=$instance")
            instance
        } catch (e: ClassNotFoundException) {
            android.util.Log.w("AuthInterceptorModule", "[authblob-diag] LavaAuthGenerated NOT FOUND (ClassNotFoundException) — falling back to StubLavaAuthBlobProvider (empty blob, NO Lava-Auth header will ever be sent): ${e.message}")
            null
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("AuthInterceptorModule", "[authblob-diag] LavaAuthGenerated reflective instantiation FAILED — falling back to stub: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
