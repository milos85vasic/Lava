package lava.downloads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.downloads.api.DownloadService
import lava.downloads.impl.DownloadServiceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DownloadsModule {

    @Binds
    @Singleton
    fun downloadService(impl: DownloadServiceImpl): DownloadService
}
