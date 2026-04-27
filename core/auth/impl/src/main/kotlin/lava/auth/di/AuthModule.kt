package lava.auth.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.auth.api.AuthService
import lava.auth.api.TokenProvider
import lava.auth.impl.AuthServiceImpl

@Module
@InstallIn(SingletonComponent::class)
internal interface AuthModule {
    @Binds
    fun authService(impl: AuthServiceImpl): AuthService

    @Binds
    fun tokenProvider(impl: AuthServiceImpl): TokenProvider
}
