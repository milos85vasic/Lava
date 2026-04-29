package lava.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import lava.database.converters.Converters
import lava.database.dao.BookmarkDao
import lava.database.dao.EndpointDao
import lava.database.dao.FavoriteSearchDao
import lava.database.dao.FavoriteTopicDao
import lava.database.dao.ForumCategoryDao
import lava.database.dao.ForumMetadataDao
import lava.database.dao.SearchHistoryDao
import lava.database.dao.SuggestDao
import lava.database.dao.VisitedTopicDao
import lava.database.entity.BookmarkEntity
import lava.database.entity.EndpointEntity
import lava.database.entity.FavoriteSearchEntity
import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.ForumCategoryEntity
import lava.database.entity.ForumMetadata
import lava.database.entity.SearchHistoryEntity
import lava.database.entity.SuggestEntity
import lava.database.entity.VisitedTopicEntity

@Database(
    entities = [
        BookmarkEntity::class,
        EndpointEntity::class,
        FavoriteSearchEntity::class,
        FavoriteTopicEntity::class,
        ForumCategoryEntity::class,
        ForumMetadata::class,
        SearchHistoryEntity::class,
        SuggestEntity::class,
        VisitedTopicEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun endpointDao(): EndpointDao
    abstract fun favoriteTopicDao(): FavoriteTopicDao
    abstract fun favoritesSearchDao(): FavoriteSearchDao
    abstract fun forumCategoryDao(): ForumCategoryDao
    abstract fun forumMetadataDao(): ForumMetadataDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun suggestDao(): SuggestDao
    abstract fun visitedTopicDao(): VisitedTopicDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `FavoriteSearch` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ForumCategoryEntity_parentId` ON `ForumCategoryEntity` (`parentId`)")
            }
        }

        /**
         * SP-3.3 (2026-04-29). User-reported: two "Main" rows in the
         * Connections list and a Mirror entry that never goes green.
         *
         * Root cause: pre-SP-3.2 the seeded set included `Endpoint.Proxy`
         * (separate row, type='Proxy'), and pre-SP-3 LAN discovery emitted
         * `Mirror(host="ip:port")` for the lava-api-go service before the
         * dedicated `GoApi(host, port)` variant landed. Both shapes outlive
         * the code that produced them — `EndpointEntity.toModel` maps
         * `type='Proxy'` to `Endpoint.Rutracker` on read, so two rows
         * collapse to one Endpoint and the user sees a duplicate "Main".
         * Mirror rows with `host` containing ':' get fed to
         * `NetworkApiRepositoryImpl.proxyApi` as `url(host="ip:port")`,
         * which does not address the lava-api-go's actual port (8443)
         * and so the connectivity probe never finds anything green.
         *
         * The migration deletes both legacy shapes outright. The user
         * re-runs LAN discovery from the menu and gets a clean GoApi row.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM Endpoint WHERE type = 'Proxy'")
                db.execSQL("DELETE FROM Endpoint WHERE type = 'Mirror' AND host LIKE '%:%'")
            }
        }
    }
}
