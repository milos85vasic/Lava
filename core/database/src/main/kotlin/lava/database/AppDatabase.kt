package lava.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import lava.database.converters.Converters
import lava.database.dao.BookmarkDao
import lava.database.dao.ClonedProviderDao
import lava.database.dao.CredentialsEntryDao
import lava.database.dao.EndpointDao
import lava.database.dao.FavoriteSearchDao
import lava.database.dao.FavoriteTopicDao
import lava.database.dao.ForumCategoryDao
import lava.database.dao.ForumMetadataDao
import lava.database.dao.ForumProviderSelectionDao
import lava.database.dao.MirrorHealthDao
import lava.database.dao.ProviderConfigDao
import lava.database.dao.ProviderCredentialBindingDao
import lava.database.dao.ProviderCredentialsDao
import lava.database.dao.ProviderSyncToggleDao
import lava.database.dao.SearchHistoryDao
import lava.database.dao.SearchProviderSelectionDao
import lava.database.dao.SuggestDao
import lava.database.dao.SyncOutboxDao
import lava.database.dao.UserMirrorDao
import lava.database.dao.VisitedTopicDao
import lava.database.entity.BookmarkEntity
import lava.database.entity.ClonedProviderEntity
import lava.database.entity.CredentialsEntryEntity
import lava.database.entity.EndpointEntity
import lava.database.entity.FavoriteSearchEntity
import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.ForumCategoryEntity
import lava.database.entity.ForumMetadata
import lava.database.entity.ForumProviderSelectionEntity
import lava.database.entity.MirrorHealthEntity
import lava.database.entity.ProviderConfigEntity
import lava.database.entity.ProviderCredentialBindingEntity
import lava.database.entity.ProviderCredentialsEntity
import lava.database.entity.ProviderSyncToggleEntity
import lava.database.entity.SearchHistoryEntity
import lava.database.entity.SearchProviderSelectionEntity
import lava.database.entity.SuggestEntity
import lava.database.entity.SyncOutboxEntity
import lava.database.entity.UserMirrorEntity
import lava.database.entity.VisitedTopicEntity

@Database(
    entities = [
        BookmarkEntity::class,
        ClonedProviderEntity::class,
        CredentialsEntryEntity::class,
        EndpointEntity::class,
        FavoriteSearchEntity::class,
        FavoriteTopicEntity::class,
        ForumCategoryEntity::class,
        ForumMetadata::class,
        ForumProviderSelectionEntity::class,
        MirrorHealthEntity::class,
        ProviderConfigEntity::class,
        ProviderCredentialBindingEntity::class,
        ProviderCredentialsEntity::class,
        ProviderSyncToggleEntity::class,
        SearchHistoryEntity::class,
        SearchProviderSelectionEntity::class,
        SuggestEntity::class,
        SyncOutboxEntity::class,
        UserMirrorEntity::class,
        VisitedTopicEntity::class,
    ],
    version = 10,
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
    abstract fun providerCredentialsDao(): ProviderCredentialsDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun searchProviderSelectionDao(): SearchProviderSelectionDao
    abstract fun forumProviderSelectionDao(): ForumProviderSelectionDao
    abstract fun credentialsEntryDao(): CredentialsEntryDao
    abstract fun providerCredentialBindingDao(): ProviderCredentialBindingDao
    abstract fun providerSyncToggleDao(): ProviderSyncToggleDao
    abstract fun clonedProviderDao(): ClonedProviderDao
    abstract fun syncOutboxDao(): SyncOutboxDao

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

        /**
         * Multi-Provider Extension (2026-05-02). Adds the credentials
         * management and provider configuration persistence layer:
         *  - `provider_credentials` — encrypted auth material per provider.
         *  - `provider_configs` — per-provider timeout/mirror/capability toggles.
         *  - `search_provider_selections` — which providers were active per search.
         *  - `forum_provider_selections` — which provider was used per category.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_credentials` (" +
                        "`provider_id` TEXT NOT NULL, " +
                        "`auth_type` TEXT NOT NULL, " +
                        "`username` TEXT, " +
                        "`encrypted_password` TEXT, " +
                        "`encrypted_token` TEXT, " +
                        "`encrypted_api_key` TEXT, " +
                        "`encrypted_api_secret` TEXT, " +
                        "`cookie_value` TEXT, " +
                        "`expires_at` INTEGER, " +
                        "`is_active` INTEGER NOT NULL, " +
                        "`last_used_at` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`provider_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_configs` (" +
                        "`provider_id` TEXT NOT NULL, " +
                        "`timeout_ms` INTEGER NOT NULL, " +
                        "`preferred_mirror_url` TEXT, " +
                        "`is_enabled` INTEGER NOT NULL, " +
                        "`search_enabled` INTEGER NOT NULL, " +
                        "`browse_enabled` INTEGER NOT NULL, " +
                        "`download_enabled` INTEGER NOT NULL, " +
                        "`sort_preference` TEXT, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`provider_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_provider_selections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`query_hash` TEXT NOT NULL, " +
                        "`provider_id` TEXT NOT NULL, " +
                        "`is_selected` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_provider_selections_query_hash` " +
                        "ON `search_provider_selections` (`query_hash`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `forum_provider_selections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`category_id` TEXT NOT NULL, " +
                        "`provider_id` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forum_provider_selections_category_id` " +
                        "ON `forum_provider_selections` (`category_id`)",
                )
            }
        }

        /**
         * SP-4 Phase A+B (Tasks 3+4). Adds five tables backing the multi-credential
         * persistence layer, per-provider sync toggle, cloned-provider catalogue,
         * and the outbox feeding the lava-api-go sync worker:
         *  - `credentials_entry` — Tink-encrypted credential blobs keyed by id.
         *  - `provider_credential_binding` — providerId -> credentialId mapping.
         *  - `provider_sync_toggle` — per-provider opt-in flag for cloud sync.
         *  - `cloned_provider` — synthetic providers cloned from a source tracker.
         *  - `sync_outbox` — append-only queue of pending sync envelopes.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS credentials_entry (" +
                        "id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, type TEXT NOT NULL, " +
                        "ciphertext BLOB NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS provider_credential_binding (" +
                        "providerId TEXT NOT NULL PRIMARY KEY, credentialId TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS provider_sync_toggle (" +
                        "providerId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cloned_provider (" +
                        "syntheticId TEXT NOT NULL PRIMARY KEY, sourceTrackerId TEXT NOT NULL, " +
                        "displayName TEXT NOT NULL, primaryUrl TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_outbox (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                        "payload TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * SP-4 Phase G (2026-05-13). Soft-delete columns on
         * [CredentialsEntryEntity] and [ClonedProviderEntity]. Existing
         * rows default to `deletedAt = NULL` (not deleted). Read paths
         * filter on `deletedAt IS NULL`; the column lets Phase E
         * propagate removals to other devices via the sync outbox AND
         * lets backup-restore survive a removal (the soft-delete marker
         * is included in the cloud backup).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE credentials_entry ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE cloned_provider ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }
    }
}
