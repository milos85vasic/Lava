package lava.network.di

import dagger.Binds
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
 * The blob-provider binding is the [StubLavaAuthBlobProvider] until
 * Phase 11 ships a generated `LavaAuthGenerated` class. After Phase 11,
 * the binding is replaced (in a sibling module) so the real per-build
 * UUID is decrypted on each request.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface AuthInterceptorModule {

    @Binds
    @Singleton
    fun bindLavaAuthBlobProvider(impl: StubLavaAuthBlobProvider): LavaAuthBlobProvider

    companion object {
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
    }
}
