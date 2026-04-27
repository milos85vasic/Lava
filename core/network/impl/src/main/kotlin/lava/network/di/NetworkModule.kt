package lava.network.di

import coil.ImageLoaderFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import lava.network.api.ImageLoader
import lava.network.api.NetworkApi
import lava.network.data.ImageLoaderFactoryImpl
import lava.network.data.NetworkApiRepository
import lava.network.data.NetworkApiRepositoryImpl
import lava.network.impl.DelegatingProxySelector
import lava.network.impl.ImageLoaderImpl
import lava.network.impl.SwitchingNetworkApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.ProxySelector
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {

    @Binds
    @Singleton
    fun imageLoader(impl: ImageLoaderImpl): ImageLoader

    @Binds
    @Singleton
    fun imageLoaderFactory(impl: ImageLoaderFactoryImpl): ImageLoaderFactory

    @Multibinds
    fun interceptors(): Set<@JvmSuppressWildcards Interceptor>

    @Binds
    @Singleton
    fun networkApi(impl: SwitchingNetworkApi): NetworkApi

    @Binds
    @Singleton
    fun networkApiRepository(impl: NetworkApiRepositoryImpl): NetworkApiRepository

    @Binds
    @Singleton
    fun proxySelector(impl: DelegatingProxySelector): ProxySelector

    companion object {
        @Provides
        @Singleton
        fun okHttpClient(
            proxySelector: ProxySelector,
            interceptors: Set<@JvmSuppressWildcards Interceptor>,
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .proxySelector(proxySelector)
                .apply { interceptors.forEach(::addInterceptor) }
                .build()
        }
    }
}
