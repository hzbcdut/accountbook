package nt.ddeoid.accountbook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.entity.TagEntity

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY sort_order ASC, name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT COUNT(*) FROM tags")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tag: TagEntity)

    @Query(
        """
        INSERT INTO tags (id, name, color, is_builtin, sort_order)
        VALUES (:id, :name, :color, :isBuiltin, :sortOrder)
        ON CONFLICT(name) DO NOTHING
        """,
    )
    suspend fun upsert(id: String, name: String, color: String, isBuiltin: Boolean, sortOrder: Int)
}