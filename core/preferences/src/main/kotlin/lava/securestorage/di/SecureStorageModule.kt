package lava.securestorage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.securestorage.PreferencesStorage
import lava.securestorage.PreferencesStorageImpl
import lava.securestorage.preferences.SharedPreferencesFactory
import lava.securestorage.preferences.SharedPreferencesFactoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface SecureStorageModule {

    @Binds
    @Singleton
    fun secureStorage(impl: PreferencesStorageImpl): PreferencesStorage

    @Binds
    @Singleton
    fun securePreferencesFactory(impl: SharedPreferencesFactoryImpl): SharedPreferencesFactory
}
