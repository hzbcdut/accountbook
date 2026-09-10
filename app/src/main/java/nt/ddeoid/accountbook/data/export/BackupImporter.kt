package nt.ddeoid.accountbook.data.export

import androidx.room.withTransaction
import nt.ddeoid.accountbook.data.export.model.AccountBookBackup
import nt.ddeoid.accountbook.data.export.model.BackupSummary
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountTagCrossRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [AccountBookBackup] 写回 Room。
 *
 * 冲突策略(Q3 = A):已经存在 (platform, account) 的行直接跳过,不覆盖用户手改的数据。
 *
 * 标签:
 * - JSON 导入:原 tagIds 指向的标签不存在于本地时,丢掉该关联(不自动建标签,避免污染)。
 * - CSV 导入:backup.accounts[*].tagIds 在 CSV 里其实是 tag 名字,本类会按名字回查后转 id。
 */
@Singleton
class BackupImporter @Inject constructor(
    private val databaseProvider: DatabaseProvider,
) {

    /** 入口:解析后的 [AccountBookBackup] → Room。 */
    suspend fun import(
        backup: AccountBookBackup,
        isCsv: Boolean = false,
    ): BackupSummary {
        // 一次导入操作里只解析一次 DAO:整个操作要么在同一个打开的库上完成,
        // 要么中途库被关掉就整体失败,不会出现半截用旧 DAO 半截用新 DAO。
        val database = databaseProvider.requireDatabase()
        val accountDao = database.accountDao()
        val tagDao = database.tagDao()

        val localTags = tagDao.listAll()
        val byName = localTags.associateBy { it.name }

        var added = 0
        var skipped = 0

        database.withTransaction {
            // 1. JSON 才需要落 tags(CSV 没带 tag 表)
            if (!isCsv) {
                backup.tags.forEach { tagExport ->
                    tagDao.upsert(
                        id = tagExport.id,
                        name = tagExport.name,
                        color = tagExport.color,
                        isBuiltin = tagExport.isBuiltin,
                        sortOrder = tagExport.sortOrder,
                    )
                }
            }

            // 2. 逐条账号去重 + 插入
            backup.accounts.forEach { acc ->
                val existing = accountDao.findByPlatformAccount(acc.platform, acc.account)
                if (existing != null) {
                    skipped++
                    return@forEach
                }

                val newId = if (acc.id.isNotBlank()) acc.id else java.util.UUID.randomUUID().toString()
                val entity = AccountEntity(
                    id = newId,
                    platform = acc.platform.trim(),
                    account = acc.account.trim(),
                    accountType = acc.accountType,
                    registeredAt = acc.registeredAt?.takeIf { it.isNotBlank() },
                    notes = acc.notes,
                    password = acc.password?.takeIf { it.isNotBlank() },
                    isActive = acc.isActive,
                    createdAt = acc.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    updatedAt = acc.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                accountDao.insert(entity)

                // 3. 关联 tag
                val resolvedTagIds: List<String> = if (isCsv) {
                    // CSV 的 tagIds 字段是名字,按名字找 id
                    acc.tagIds.mapNotNull { name -> byName[name]?.id }
                } else {
                    acc.tagIds
                }
                resolvedTagIds.forEach { tagId ->
                    accountDao.insertTagCrossRef(AccountTagCrossRef(newId, tagId))
                }
                added++
            }
        }

        return BackupSummary(
            accountsTotal = backup.accounts.size,
            accountsAdded = added,
            accountsSkipped = skipped,
            tagsTotal = backup.tags.size,
            platformsTotal = backup.platforms.size,
        )
    }
}
