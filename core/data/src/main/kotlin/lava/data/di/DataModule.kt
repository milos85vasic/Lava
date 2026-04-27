package lava.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.data.api.repository.BookmarksRepository
import lava.data.api.repository.EndpointsRepository
import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.ForumRepository
import lava.data.api.repository.RatingRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.data.api.repository.SettingsRepository
import lava.data.api.repository.SuggestsRepository
import lava.data.api.repository.VisitedRepository
import lava.data.api.service.ConnectionService
import lava.data.api.service.FavoritesService
import lava.data.api.service.ForumService
import lava.data.api.service.LocalNetworkDiscoveryService
import lava.data.api.service.SearchService
import lava.data.api.service.StoreService
import lava.data.api.service.TopicService
import lava.data.api.service.TorrentService
import lava.data.impl.repository.BookmarksRepositoryImpl
import lava.data.impl.repository.EndpointsRepositoryImpl
import lava.data.impl.repository.FavoriteSearchRepositoryImpl
import lava.data.impl.repository.FavoritesRepositoryImpl
import lava.data.impl.repository.ForumRepositoryImpl
import lava.data.impl.repository.RatingRepositoryImpl
import lava.data.impl.repository.SearchHistoryRepositoryImpl
import lava.data.impl.repository.SettingsRepositoryImpl
import lava.data.impl.repository.SuggestsRepositoryImpl
import lava.data.impl.repository.VisitedRepositoryImpl
import lava.data.impl.service.ConnectionServiceImpl
import lava.data.impl.service.FavoritesServiceImpl
import lava.data.impl.service.ForumServiceImpl
import lava.data.impl.service.LocalNetworkDiscoveryServiceImpl
import lava.data.impl.service.SearchServiceImpl
import lava.data.impl.service.StoreServiceImpl
import lava.data.impl.service.TopicServiceImpl
import lava.data.impl.service.TorrentServiceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    @Singleton
    fun bookmarksRepository(impl: BookmarksRepositoryImpl): BookmarksRepository

    @Binds
    @Singleton
    fun endpointsRepository(impl: EndpointsRepositoryImpl): EndpointsRepository

    @Binds
    @Singleton
    fun favoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds
    @Singleton
    fun favoritesService(impl: FavoritesServiceImpl): FavoritesService

    @Binds
    @Singleton
    fun forumRepository(impl: ForumRepositoryImpl): ForumRepository

    @Binds
    @Singleton
    fun forumService(impl: ForumServiceImpl): ForumService

    @Binds
    @Singleton
    fun localNetworkDiscoveryService(impl: LocalNetworkDiscoveryServiceImpl): LocalNetworkDiscoveryService

    @Binds
    @Singleton
    fun networkConnectionService(impl: ConnectionServiceImpl): ConnectionService

    @Binds
    @Singleton
    fun ratingRepository(impl: RatingRepositoryImpl): RatingRepository

    @Binds
    @Singleton
    fun searchHistoryRepository(impl: SearchHistoryRepositoryImpl): SearchHistoryRepository

    @Binds
    @Singleton
    fun searchService(impl: SearchServiceImpl): SearchService

    @Binds
    @Singleton
    fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    fun suggestsRepository(impl: SuggestsRepositoryImpl): SuggestsRepository

    @Binds
    @Singleton
    fun storeService(impl: StoreServiceImpl): StoreService

    @Binds
    @Singleton
    fun topicService(impl: TopicServiceImpl): TopicService

    @Binds
    @Singleton
    fun torrentService(impl: TorrentServiceImpl): TorrentService

    @Binds
    @Singleton
    fun visitedRepository(impl: VisitedRepositoryImpl): VisitedRepository

    @Binds
    @Singleton
    fun favoriteSearchRepository(impl: FavoriteSearchRepositoryImpl): FavoriteSearchRepository
}
