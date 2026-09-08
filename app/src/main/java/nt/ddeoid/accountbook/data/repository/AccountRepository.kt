package nt.ddeoid.accountbook.data.repository

import kotlinx.coroutines.flow.Flow
import nt.ddeoid.accountbook.data.local.dao.AccountDao
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
 * - [upsert] / [delete] / [setActive] 是 Phase 2 CRUD 的入口;Phase 3 会加导入导出。
 * - 自动更新 [PlatformCatalogDao] 的 usage_count / last_used_at。
 */
@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val platformCatalogDao: PlatformCatalogDao,
) {

    fun observeActiveAccountsWithTags(): Flow<List<AccountWithTags>> =
        accountDao.observeActiveAccountsWithTags()

    fun observeAccountWithTags(id: String): Flow<AccountWithTags?> =
        accountDao.observeWithTags(id)

    suspend fun upsert(
        account: AccountEntity,
        tagIds: List<String>,
    ) {
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
        accountDao.deleteById(id)
    }

    suspend fun setActive(id: String, active: Boolean) {
        accountDao.setActive(id, active, System.currentTimeMillis())
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}