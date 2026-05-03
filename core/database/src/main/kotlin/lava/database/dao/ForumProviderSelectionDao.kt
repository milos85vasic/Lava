package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ForumProviderSelectionEntity

@Dao
interface ForumProviderSelectionDao {

    @Query("SELECT * FROM forum_provider_selections WHERE category_id = :categoryId")
    suspend fun loadForCategory(categoryId: String): ForumProviderSelectionEntity?

    @Query("SELECT * FROM forum_provider_selections WHERE category_id = :categoryId")
    fun observeForCategory(categoryId: String): Flow<ForumProviderSelectionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ForumProviderSelectionEntity)

    @Query("DELETE FROM forum_provider_selections WHERE category_id = :categoryId")
    suspend fun deleteForCategory(categoryId: String)
}
