/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package lava.database.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.database.AppDatabase
import lava.database.dao.BookmarkDao
import lava.database.dao.EndpointDao
import lava.database.dao.FavoriteSearchDao
import lava.database.dao.FavoriteTopicDao
import lava.database.dao.ForumCategoryDao
import lava.database.dao.ForumMetadataDao
import lava.database.dao.ForumProviderSelectionDao
import lava.database.dao.MirrorHealthDao
import lava.database.dao.ProviderConfigDao
import lava.database.dao.ProviderCredentialsDao
import lava.database.dao.SearchHistoryDao
import lava.database.dao.SearchProviderSelectionDao
import lava.database.dao.SuggestDao
import lava.database.dao.UserMirrorDao
import lava.database.dao.VisitedTopicDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DaosModule {
    @Provides
    @Singleton
    fun providesBookmarkDao(appDatabase: AppDatabase): BookmarkDao = appDatabase.bookmarkDao()

    @Provides
    @Singleton
    fun providesEndpointDao(appDatabase: AppDatabase): EndpointDao = appDatabase.endpointDao()

    @Provides
    @Singleton
    fun providesFavoriteSearchDao(appDatabase: AppDatabase): FavoriteSearchDao =
        appDatabase.favoritesSearchDao()

    @Provides
    @Singleton
    fun providesFavoriteTopicDao(appDatabase: AppDatabase): FavoriteTopicDao =
        appDatabase.favoriteTopicDao()

    @Provides
    @Singleton
    fun providesForumCategoryDao(appDatabase: AppDatabase): ForumCategoryDao =
        appDatabase.forumCategoryDao()

    @Provides
    @Singleton
    fun providesForumMetadataDao(appDatabase: AppDatabase): ForumMetadataDao =
        appDatabase.forumMetadataDao()

    @Provides
    @Singleton
    fun providesSearchHistoryDao(appDatabase: AppDatabase): SearchHistoryDao =
        appDatabase.searchHistoryDao()

    @Provides
    @Singleton
    fun providesSuggestDao(appDatabase: AppDatabase): SuggestDao = appDatabase.suggestDao()

    @Provides
    @Singleton
    fun providesVisitedTopicDao(appDatabase: AppDatabase): VisitedTopicDao =
        appDatabase.visitedTopicDao()

    @Provides
    @Singleton
    fun providesMirrorHealthDao(appDatabase: AppDatabase): MirrorHealthDao =
        appDatabase.mirrorHealthDao()

    @Provides
    @Singleton
    fun providesUserMirrorDao(appDatabase: AppDatabase): UserMirrorDao =
        appDatabase.userMirrorDao()

    @Provides
    @Singleton
    fun providesProviderCredentialsDao(appDatabase: AppDatabase): ProviderCredentialsDao =
        appDatabase.providerCredentialsDao()

    @Provides
    @Singleton
    fun providesProviderConfigDao(appDatabase: AppDatabase): ProviderConfigDao =
        appDatabase.providerConfigDao()

    @Provides
    @Singleton
    fun providesSearchProviderSelectionDao(appDatabase: AppDatabase): SearchProviderSelectionDao =
        appDatabase.searchProviderSelectionDao()

    @Provides
    @Singleton
    fun providesForumProviderSelectionDao(appDatabase: AppDatabase): ForumProviderSelectionDao =
        appDatabase.forumProviderSelectionDao()
}
