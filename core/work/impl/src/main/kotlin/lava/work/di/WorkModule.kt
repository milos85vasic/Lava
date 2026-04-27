package lava.work.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lava.work.api.BackgroundService
import lava.work.impl.WorkBackgroundService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface WorkModule {
    @Binds
    @Singleton
    fun backgroundService(impl: WorkBackgroundService): BackgroundService

    companion object {
        @Provides
        @Singleton
        fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}
