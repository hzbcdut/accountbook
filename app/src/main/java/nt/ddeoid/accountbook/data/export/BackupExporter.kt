package nt.ddeoid.accountbook.data.export

import kotlinx.coroutines.flow.first
import nt.ddeoid.accountbook.data.export.model.AccountBookBackup
import nt.ddeoid.accountbook.data.export.model.AccountExport
import nt.ddeoid.accountbook.data.export.model.PlatformExport
import nt.ddeoid.accountbook.data.export.model.TagExport
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.dao.TagDao
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
 * 注意:这里不打码不加密 —— 备份文件是用户主动导出到外部存储的明文 JSON / CSV,
 * 敏感数据由用户在导出确认对话框里被警告。生产环境的"备份"是另外一条路(SQLCipher 数据库
 * 自身的拷贝),见 Phase 7 备份设计。
 */
@Singleton
class BackupExporter @Inject constructor(
    private val accountDao: AccountDao,
    private val tagDao: TagDao,
    private val platformCatalogDao: PlatformCatalogDao,
) {

    suspend fun snapshot(): AccountBookBackup {
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
