package nt.ddeoid.accountbook.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountTagCrossRef
import nt.ddeoid.accountbook.data.local.entity.TagEntity

/**
 * Account 表的访问层。
 *
 * 注意:
 * - 全文搜索 SQL 留给 Phase 2 接 FTS4;Phase 1 仅做最简单的 `LIKE`。
 * - 标签筛选走 [accountsWithTags] 这条联表查询。
 */
@Dao
interface AccountDao {

    @Query(
        """
        SELECT a.* FROM accounts a
        WHERE a.is_active = 1
        ORDER BY a.platform ASC, a.updated_at DESC
        """,
    )
    fun observeActiveAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE accounts SET is_active = :active, updated_at = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    /** 关联表写入。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagCrossRef(ref: AccountTagCrossRef)

    @Query("DELETE FROM account_tag_xref WHERE account_id = :accountId")
    suspend fun clearTagsFor(accountId: String)

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN account_tag_xref x ON x.tag_id = t.id
        WHERE x.account_id = :accountId
        ORDER BY t.sort_order ASC, t.name ASC
        """,
    )
    suspend fun tagsFor(accountId: String): List<TagEntity>

    @Transaction
    suspend fun replaceTagsFor(accountId: String, tagIds: List<String>) {
        clearTagsFor(accountId)
        tagIds.forEach { insertTagCrossRef(AccountTagCrossRef(accountId, it)) }
    }
}