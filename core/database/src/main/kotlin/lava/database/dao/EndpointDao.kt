package lava.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.EndpointEntity

@Dao
interface EndpointDao {
    @Query("SELECT (SELECT COUNT(*) FROM Endpoint) == 0")
    suspend fun isEmpty(): Boolean

    @Query("SELECT * FROM Endpoint")
    fun observerAll(): Flow<List<EndpointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(endpoint: EndpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(endpoints: List<EndpointEntity>)

    @Delete
    suspend fun remove(endpoint: EndpointEntity)
}
