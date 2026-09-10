package nt.ddeoid.accountbook.data.export.model

import kotlinx.serialization.Serializable
import nt.ddeoid.accountbook.data.local.entity.AccountType

/**
 * AccountBook 备份的统一数据结构。
 *
 * - JSON 格式完整保留:账号 + 标签 + 平台目录 + 关系(tagIds)。
 * - CSV 格式只导出 accounts 段,标签以 "name1;name2" 内联在每行;tags / platforms 由
 *   [nt.ddeoid.accountbook.data.export.codec.CsvBackupCodec] 在导入时按名字回查。
 *
 * [schemaVersion] 递增代表不向后兼容的格式变更;导入端如果发现 major 升级,直接拒绝。
 */
@Serializable
data class AccountBookBackup(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long,
    val app: String = APP_NAME,
    val accounts: List<AccountExport> = emptyList(),
    val tags: List<TagExport> = emptyList(),
    val platforms: List<PlatformExport> = emptyList(),
) {
    companion object {
        const val APP_NAME: String = "AccountBook"
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class AccountExport(
    val id: String,
    val platform: String,
    val account: String,
    val accountType: AccountType,
    val registeredAt: String? = null,
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val tagIds: List<String> = emptyList(),
    /**
     * v0.5.0 新增。默认 `null`,所以旧版备份 JSON(没有这个字段)也能被新版读回来,
     * 不会破坏现有用户的备份。CSV 同理 —— 旧版 CSV 没有这一列,新版解析时按缺列处理
     * (字段下标 > 实际列数时取 null)。
     */
    val password: String? = null,
)

@Serializable
data class TagExport(
    val id: String,
    val name: String,
    val color: String,
    val sortOrder: Int,
    val isBuiltin: Boolean,
)

@Serializable
data class PlatformExport(
    val id: String,
    val name: String,
    val usageCount: Int,
    val isCustom: Boolean,
    val lastUsedAt: Long,
)

/** 导入/导出完成后给 UI 看的统计。 */
data class BackupSummary(
    val accountsTotal: Int,
    val accountsAdded: Int,
    val accountsSkipped: Int,
    val tagsTotal: Int,
    val platformsTotal: Int,
) {
    val skippedAny: Boolean get() = accountsSkipped > 0
}
