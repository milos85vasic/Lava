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
import lava.database.dao.MirrorHealthDao
import lava.database.dao.SearchHistoryDao
import lava.database.dao.SuggestDao
import lava.database.dao.UserMirrorDao
import lava.database.dao.VisitedTopicDao
import lava.database.entity.BookmarkEntity
import lava.database.entity.EndpointEntity
import lava.database.entity.FavoriteSearchEntity
import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.ForumCategoryEntity
import lava.database.entity.ForumMetadata
import lava.database.entity.MirrorHealthEntity
import lava.database.entity.SearchHistoryEntity
import lava.database.entity.SuggestEntity
import lava.database.entity.UserMirrorEntity
import lava.database.entity.VisitedTopicEntity

@Database(
    entities = [
        BookmarkEntity::class,
        EndpointEntity::class,
        FavoriteSearchEntity::class,
        FavoriteTopicEntity::class,
        ForumCategoryEntity::class,
        ForumMetadata::class,
        MirrorHealthEntity::class,
        SearchHistoryEntity::class,
        SuggestEntity::class,
        UserMirrorEntity::class,
        VisitedTopicEntity::class,
    ],
    version = 7,
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
    abstract fun mirrorHealthDao(): MirrorHealthDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun suggestDao(): SuggestDao
    abstract fun userMirrorDao(): UserMirrorDao
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

        /**
         * SP-3a Phase 4 (Task 4.1, 2026-04-30). Adds the multi-tracker SDK
         * persistence layer:
         *  - `tracker_mirror_health` — per-(tracker, mirror) HEALTHY/DEGRADED/
         *    UNHEALTHY/UNKNOWN snapshot, populated by the periodic
         *    `MirrorHealthCheckWorker` and rehydrated into the SDK's
         *    in-memory `MirrorManager` on app start.
         *  - `tracker_mirror_user` — user-supplied custom mirror URLs that
         *    layer on top of the bundled `mirrors.json` (user entries
         *    supersede bundled at the same URL).
         *
         * Both tables use composite (tracker_id, mirror_url|url) primary keys
         * to allow the same URL across trackers without collision while
         * rejecting duplicates within a single tracker.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracker_mirror_health` (" +
                        "`tracker_id` TEXT NOT NULL, " +
                        "`mirror_url` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`last_check_at` INTEGER, " +
                        "`consecutive_failures` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tracker_id`, `mirror_url`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracker_mirror_user` (" +
                        "`tracker_id` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`protocol` TEXT NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tracker_id`, `url`))",
                )
            }
        }
    }
}
