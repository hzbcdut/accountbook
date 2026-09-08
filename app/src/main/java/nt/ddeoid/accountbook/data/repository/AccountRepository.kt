package nt.ddeoid.accountbook.data.repository

import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account 仓库。
 *
 * - 暴露 [observeActiveAccountsWithTags] 给 UI 直接消费。
 * - [upsert] / [delete] / [setActive] 是 CRUD 入口。
 * - 自动更新 [PlatformCatalogDao] 的 usage_count / last_used_at。
 *
 * DAO 不再直接注入,而是每次通过 [DatabaseProvider] 解析(Q4=C:库可以被关掉,
 * 长期持有 DAO 会攥着一个指向已关闭连接的野指针)。观察型方法用
 * [DatabaseProvider.deferred] 把解析推迟到**订阅时**,所以构造这个仓库不需要库已打开。
 */
@Singleton
class AccountRepository @Inject constructor(
    private val databaseProvider: DatabaseProvider,
) {

    fun observeActiveAccountsWithTags(): Flow<List<AccountWithTags>> =
        databaseProvider.deferred { it.accountDao().observeActiveAccountsWithTags() }

    fun observeAccountWithTags(id: String): Flow<AccountWithTags?> =
        databaseProvider.deferred { it.accountDao().observeWithTags(id) }

    suspend fun upsert(
        account: AccountEntity,
        tagIds: List<String>,
    ) {
        val accountDao = databaseProvider.accountDao()
        val platformCatalogDao = databaseProvider.platformCatalogDao()

        val now = System.currentTimeMillis()
        val withTimestamps = account.copy(
            updatedAt = now,
            createdAt = if (account.createdAt == 0L) now else account.createdAt,
        )
        // 新增 vs 更新分流
        val existing = accountDao.findById(withTimestamps.id)
        if (existing == null) {
            accountDao.insert(withTimestamps)
        } else {
            accountDao.update(withTimestamps)
        }
        accountDao.replaceTagsFor(withTimestamps.id, tagIds)

        // 平台目录维护
        platformCatalogDao.upsertCustom(
            id = UUID.nameUUIDFromBytes(withTimestamps.platform.toByteArray()).toString(),
            name = withTimestamps.platform,
            now = now,
        )
        platformCatalogDao.bumpUsage(withTimestamps.platform, now)
    }

    suspend fun delete(id: String) {
        databaseProvider.accountDao().deleteById(id)
    }

    suspend fun setActive(id: String, active: Boolean) {
        databaseProvider.accountDao().setActive(id, active, System.currentTimeMillis())
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
