package nt.ddeoid.accountbook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity

@Dao
interface PlatformCatalogDao {

    /** 自动补全:按使用频次 + 最近使用时间倒序,`is_custom = 0` 的内置条目也参与。 */
    @Query(
        """
        SELECT * FROM platform_catalog
        WHERE name LIKE '%' || :query || '%'
        ORDER BY usage_count DESC, last_used_at DESC
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 10): Flow<List<PlatformCatalogEntity>>

    @Query("SELECT * FROM platform_catalog ORDER BY usage_count DESC, last_used_at DESC")
    fun observeAll(): Flow<List<PlatformCatalogEntity>>

    @Query("SELECT COUNT(*) FROM platform_catalog")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM platform_catalog WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): PlatformCatalogEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: PlatformCatalogEntity)

    /**
     * 新增一个用户自定义平台,如果同名已存在则复用其 id 并更新 lastUsedAt。
     */
    @Query(
        """
        INSERT INTO platform_catalog (id, name, usage_count, is_custom, last_used_at)
        VALUES (:id, :name, 0, 1, :now)
        ON CONFLICT(name) DO UPDATE SET
            is_custom = 1,
            last_used_at = MAX(last_used_at, :now)
        """,
    )
    suspend fun upsertCustom(id: String, name: String, now: Long)

    @Query(
        """
        UPDATE platform_catalog
        SET usage_count = usage_count + 1, last_used_at = :now
        WHERE name = :name
        """,
    )
    suspend fun bumpUsage(name: String, now: Long)
}