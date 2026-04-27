package lava.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.FavoriteSearchEntity

@Dao
interface FavoriteSearchDao {
    @Query("SELECT * FROM FavoriteSearch")
    fun observerAll(): Flow<List<FavoriteSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteSearchEntity)

    @Delete
    suspend fun delete(entity: FavoriteSearchEntity)

    @Query("DELETE FROM FavoriteSearch")
    suspend fun deleteAll()
}
