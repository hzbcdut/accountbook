package nt.ddeoid.accountbook.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountTagCrossRef
import nt.ddeoid.accountbook.data.local.entity.TagEntity

/**
 * Account 表的访问层。
 *
 * 涉及多表的关联查询(账号 + 标签)走 [AccountWithTags],Phase 2 用 Room 的 @Relation/Junction 自动 join。
 */
@Dao
interface AccountDao {

    /**
     * 所有"启用中"的账号,带它们的标签。Phase 2 主界面直接消费这个 Flow。
     *
     * 排序:按平台名字典序,组内按 updated_at 倒序。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM accounts
        WHERE is_active = 1
        ORDER BY platform COLLATE NOCASE ASC, updated_at DESC
        """,
    )
    fun observeActiveAccountsWithTags(): Flow<List<AccountWithTags>>

    @Transaction
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    fun observeWithTags(id: String): Flow<AccountWithTags?>

    @Transaction
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findWithTags(id: String): AccountWithTags?

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AccountEntity?

    /** 备份导出用:包含已停用账号。 */
    @Transaction
    @Query("SELECT * FROM accounts ORDER BY platform COLLATE NOCASE ASC, updated_at DESC")
    fun observeAllAccountsWithTags(): Flow<List<AccountWithTags>>

    @Transaction
    @Query("SELECT * FROM accounts ORDER BY platform COLLATE NOCASE ASC, updated_at DESC")
    suspend fun listAllWithTags(): List<AccountWithTags>

    /** 备份导入去重用:查 (platform, account) 是否存在。 */
    @Query(
        """
        SELECT * FROM accounts
        WHERE LOWER(platform) = LOWER(:platform) AND LOWER(account) = LOWER(:account)
        LIMIT 1
        """,
    )
    suspend fun findByPlatformAccount(platform: String, account: String): AccountEntity?

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

/**
 * 账号 + 该账号上挂的所有标签。Room 在 [AccountDao.observeActiveAccountsWithTags] 里自动 join。
 */
data class AccountWithTags(
    @Embedded val account: AccountEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = AccountTagCrossRef::class,
            parentColumn = "account_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
)