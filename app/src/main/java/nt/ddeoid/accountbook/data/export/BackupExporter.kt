package nt.ddeoid.accountbook.data.export

import kotlinx.coroutines.flow.first
import nt.ddeoid.accountbook.data.export.model.AccountBookBackup
import nt.ddeoid.accountbook.data.export.model.AccountExport
import nt.ddeoid.accountbook.data.export.model.PlatformExport
import nt.ddeoid.accountbook.data.export.model.TagExport
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把当前 Room 数据库里的全部数据打包成 [AccountBookBackup]。
 *
 * 包含:
 * - 所有账号(active + inactive)
 * - 所有标签(含内置)
 * - 所有平台目录(含内置)
 *
 * ⚠️ 这里**不打码不加密** —— 产出的是用户主动导出到外部存储的明文 JSON / CSV,
 * 敏感性由导出前的确认对话框(Q7=A 决定不额外加 re-auth,但保留警告)提示用户。
 *
 * Phase 4 另有一条**加密备份**路径:直接把 SQLCipher 的 `accountbook.db` 文件本身
 * 复制出去 —— 它已经是用 master key 加密的容器,配合 12 词助记词就能在新设备上完整
 * 还原。那条路不经过这个类。
 */
@Singleton
class BackupExporter @Inject constructor(
    private val databaseProvider: DatabaseProvider,
) {

    suspend fun snapshot(): AccountBookBackup {
        // 一次快照只解析一次 DAO,保证三个查询落在同一个打开的库上。
        val database = databaseProvider.requireDatabase()
        val accountDao = database.accountDao()
        val tagDao = database.tagDao()
        val platformCatalogDao = database.platformCatalogDao()

        val accountRows = accountDao.listAllWithTags()
        val tagRows = tagDao.listAll()
        val platformRows = platformCatalogDao.observeAll().first()

        return AccountBookBackup(
            exportedAt = System.currentTimeMillis(),
            accounts = accountRows.map { row ->
                AccountExport(
                    id = row.account.id,
                    platform = row.account.platform,
                    account = row.account.account,
                    accountType = row.account.accountType,
                    registeredAt = row.account.registeredAt,
                    notes = row.account.notes,
                    isActive = row.account.isActive,
                    createdAt = row.account.createdAt,
                    updatedAt = row.account.updatedAt,
                    tagIds = row.tags.map { it.id },
                )
            },
            tags = tagRows.map { TagExport(it.id, it.name, it.color, it.sortOrder, it.isBuiltin) },
            platforms = platformRows.map {
                PlatformExport(it.id, it.name, it.usageCount, it.isCustom, it.lastUsedAt)
            },
        )
    }
}
