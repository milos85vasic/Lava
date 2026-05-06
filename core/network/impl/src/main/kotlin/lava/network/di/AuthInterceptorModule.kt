package lava.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
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
    ): Interceptor = AuthInterceptor(
        blobProvider = blobProvider,
        signingCertHash = AuthInterceptor.SigningCertHash { signingCertProvider.sha256() },
    )

    /**
     * Returns the Phase-11-generated [LavaAuthBlobProvider] if its
     * class is on the classpath; null otherwise. Reflection is used
     * once at boot (singleton) so the per-request hot path is
     * unaffected.
     */
    private fun tryLoadGenerated(): LavaAuthBlobProvider? {
        return try {
            val cls = Class.forName("lava.auth.LavaAuthGenerated")
            cls.getDeclaredConstructor().newInstance() as? LavaAuthBlobProvider
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
