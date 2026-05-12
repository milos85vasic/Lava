package lava.credentials.di

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsEntryRepository
import lava.credentials.CredentialsEntryRepositoryImpl
import lava.credentials.PassphraseManager
import lava.credentials.ProviderCredentialBinding
import lava.credentials.ProviderCredentialBindingImpl
import lava.credentials.SharedPrefsPassphraseStorage
import lava.credentials.session.CredentialsKeyHolder
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module for the credentials management layer.
 *
 * Added in Multi-Provider Extension (Task 6.6); extended in SP-4 Phase A+B
 * (Task 9) with bindings for the new credentials-entry repository, provider
 * credential binding, passphrase storage, and the credentials key provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object CredentialsModule {

    @Provides
    @Singleton
    fun provideCredentialEncryptor(): CredentialEncryptor = CredentialEncryptor()

    /**
     * The credentials passphrase preferences file. Uses EncryptedSharedPreferences
     * when available; falls back to MODE_PRIVATE shared preferences if the
     * AndroidX Security library cannot initialize a master key (matches the
     * fallback strategy in core:preferences SharedPreferencesFactoryImpl).
     */
    @Provides
    @Singleton
    @Named(PASSPHRASE_PREFS)
    fun providePassphrasePreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences = runCatching {
        val mainKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PASSPHRASE_PREFS_FILE,
            mainKey,
            AES256_SIV,
            AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(PASSPHRASE_PREFS_FILE, MODE_PRIVATE)
    }

    /**
     * Key provider used by [CredentialsEntryRepositoryImpl]. Delegates to
     * [CredentialsKeyHolder.require], which throws if the user has not unlocked
     * the credentials key holder yet. Callers MUST prompt for the passphrase
     * before invoking encrypted operations.
     */
    @Provides
    @Singleton
    fun provideCredentialsKeyProvider(holder: CredentialsKeyHolder): () -> ByteArray =
        { holder.require() }

    const val PASSPHRASE_PREFS = "credentials_passphrase_prefs"
    private const val PASSPHRASE_PREFS_FILE = "credentials_passphrase_v1"
}

/**
 * @Binds wiring for SP-4 Phase A+B credentials types. Lives in a separate
 * abstract-class module so it can coexist with the existing object-style
 * CredentialsModule providers above.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialsBindsModule {

    @Binds
    @Singleton
    abstract fun bindCredentialsEntryRepository(
        impl: CredentialsEntryRepositoryImpl,
    ): CredentialsEntryRepository

    @Binds
    @Singleton
    abstract fun bindProviderCredentialBinding(
        impl: ProviderCredentialBindingImpl,
    ): ProviderCredentialBinding

    @Binds
    @Singleton
    abstract fun bindPassphraseStorage(
        impl: SharedPrefsPassphraseStorage,
    ): PassphraseManager.Storage
}
