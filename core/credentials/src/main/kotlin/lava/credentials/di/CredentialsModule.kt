package lava.credentials.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.credentials.CredentialEncryptor
import javax.inject.Singleton

/**
 * Hilt module for the credentials management layer.
 *
 * Added in Multi-Provider Extension (Task 6.6).
 */
@Module
@InstallIn(SingletonComponent::class)
object CredentialsModule {

    @Provides
    @Singleton
    fun provideCredentialEncryptor(): CredentialEncryptor = CredentialEncryptor()
}
