package lava.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindSyncOutbox(impl: SyncOutboxImpl): SyncOutbox
}
