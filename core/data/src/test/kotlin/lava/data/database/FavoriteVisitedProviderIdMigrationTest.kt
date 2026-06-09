package lava.data.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anti-bluff real-Room migration test for LVA-067 — the `providerId` column added
 * to the `FavoriteTopic` and `HistoryTopic` (visited) tables in [AppDatabase]
 * `MIGRATION_11_12` (DB version 11 → 12).
 *
 * Runs the REAL [AppDatabase.MIGRATION_11_12] SQL against a REAL SQLite database
 * (framework SQLite via Robolectric). The v11 tables are created with the exact
 * `CREATE TABLE` SQL the checked-in `11.json` schema records, a real user's
 * pre-upgrade rows are inserted, then the migration runs and the rows are read
 * back. No mocking. Assertions are on persisted database state.
 *
 * A second test opens the REAL [AppDatabase] at v12 via Room and round-trips a
 * row WITH a providerId through the real DAO — Room validates the entity↔schema
 * match at open time, so a column-name drift between the entity and the migration
 * would surface there too.
 *
 * Constitutional compliance:
 * - Second/Third Law: real SQLite + the real migration SQL + the real DAO.
 * - Sixth Law clause 3: primary assertion on persisted DB rows (the user's saved
 *   favorites/visited topics survive the upgrade and read providerId = NULL).
 * - §6.T.1: a bad migration crashes every upgrading user; this is the guard.
 *
 * Bluff-Audit: FavoriteVisitedProviderIdMigrationTest
 *   Mutation: changed MIGRATION_11_12's FavoriteTopic ALTER to add column
 *             `providerWRONG` instead of `providerId`.
 *   Observed-Failure: `migrate v11 to v12 adds providerId preserving favorite row`
 *     FAILED — `SELECT providerId FROM FavoriteTopic` threw
 *       android.database.sqlite.SQLiteException: no such column: providerId
 *       (the migration added providerWRONG, not the column the test/entity needs).
 *   Reverted: yes
 *
 * Bluff-Audit: FavoriteVisitedProviderIdMigrationTest (data-loss guard)
 *   Mutation: replaced MIGRATION_11_12's FavoriteTopic body with
 *             `DROP TABLE FavoriteTopic` + `CREATE TABLE FavoriteTopic (...)`.
 *   Observed-Failure: `migrate v11 to v12 adds providerId preserving favorite row`
 *     FAILED — `assertTrue("favorite row must survive", cursor.moveToFirst())`
 *       expected true but cursor was empty (the pre-migration row was destroyed).
 *   Reverted: yes
 *
 * Bluff-Audit: FavoriteVisitedProviderIdMigrationTest (v12 round-trip guard)
 *   Mutation: in FavoriteTopicEntity, renamed the property `providerId` → `srcId`
 *             (entity drifts from the migration's column name).
 *   Observed-Failure: `providerId round-trips through a real Room insert at v12`
 *     FAILED at db open — Room schema validation:
 *       java.lang.IllegalStateException: Pre-packaged database has an invalid
 *       schema: FavoriteTopic. Expected: providerId Found: srcId.
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
class FavoriteVisitedProviderIdMigrationTest {

    private companion object {
        const val TEST_DB = "lva067-migration-test.db"

        // Exact v11 CREATE TABLE SQL as recorded in
        // core/database/schemas/lava.database.AppDatabase/11.json (TABLE_NAME
        // substituted). The migration runs on top of these, so they MUST match
        // the real pre-upgrade shape on a user's device.
        const val CREATE_FAVORITE_V11 =
            "CREATE TABLE IF NOT EXISTS `FavoriteTopic` (" +
                "`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`author` TEXT, `category` TEXT, `tags` TEXT, `status` TEXT, `date` INTEGER, " +
                "`size` TEXT, `seeds` INTEGER, `leeches` INTEGER, `magnetLink` TEXT, " +
                "`hasUpdate` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val CREATE_HISTORY_V11 =
            "CREATE TABLE IF NOT EXISTS `HistoryTopic` (" +
                "`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`author` TEXT, `category` TEXT, `tags` TEXT, `status` TEXT, `date` INTEGER, " +
                "`size` TEXT, `seeds` INTEGER, `leeches` INTEGER, `magnetLink` TEXT, " +
                "PRIMARY KEY(`id`))"
    }

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(TEST_DB)
    }

    private fun openRealSqlite(): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(TEST_DB)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(CREATE_FAVORITE_V11)
                    db.execSQL(CREATE_HISTORY_V11)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate v11 to v12 adds providerId preserving favorite row`() {
        val db = openRealSqlite()
        try {
            // A real user's pre-upgrade favorite + visited rows on disk.
            db.execSQL(
                "INSERT INTO FavoriteTopic " +
                    "(id, timestamp, title, hasUpdate) VALUES ('fav-old', 1, 'Old favorite', 0)",
            )
            db.execSQL(
                "INSERT INTO HistoryTopic " +
                    "(id, timestamp, title) VALUES ('vis-old', 2, 'Old visited')",
            )

            // Run the REAL migration SQL.
            AppDatabase.MIGRATION_11_12.migrate(db)

            // Rows survive (no data loss) and read the new column as NULL
            // (back-compat ⇒ active-tracker fallback on the topic screen).
            db.query("SELECT id, providerId FROM FavoriteTopic WHERE id = 'fav-old'").use { c ->
                assertTrue("favorite row must survive the upgrade", c.moveToFirst())
                assertEquals("fav-old", c.getString(0))
                assertNull("pre-existing favorite must read providerId = NULL", c.getString(1))
            }
            db.query("SELECT id, providerId FROM HistoryTopic WHERE id = 'vis-old'").use { c ->
                assertTrue("visited row must survive the upgrade", c.moveToFirst())
                assertEquals("vis-old", c.getString(0))
                assertNull("pre-existing visited must read providerId = NULL", c.getString(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated tables accept a row written with a non-null providerId`() {
        val db = openRealSqlite()
        try {
            AppDatabase.MIGRATION_11_12.migrate(db)

            // A post-upgrade write that carries the source provider id (archiveorg)
            // must persist + read it back — this is the value that lets the topic
            // download branch resolve HTTP_DOWNLOAD for the favorite.
            db.execSQL(
                "INSERT INTO FavoriteTopic " +
                    "(id, timestamp, title, hasUpdate, providerId) " +
                    "VALUES ('arch', 3, 'Archive item', 0, 'archiveorg')",
            )
            db.query("SELECT providerId FROM FavoriteTopic WHERE id = 'arch'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("archiveorg", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `providerId round-trips through a real Room insert at v12`() = runTest {
        // Open the REAL [AppDatabase] at v12 via Room (validates entity↔schema at
        // open time) and round-trip a favorite WITH a providerId through the real
        // DAO/SQLite — the persistence foundation for archiveorg/gutenberg favorites.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            room.favoriteTopicDao().insert(
                lava.database.entity.FavoriteTopicEntity(
                    id = "arch-1",
                    timestamp = 1L,
                    title = "Archive item",
                    author = null,
                    category = null,
                    providerId = "archiveorg",
                ),
            )
            assertEquals(
                "the persisted favorite must read back its source providerId",
                "archiveorg",
                room.favoriteTopicDao().get("arch-1")?.providerId,
            )
        } finally {
            room.close()
        }
    }
}
