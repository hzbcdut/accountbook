package nt.ddeoid.accountbook.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一行 = 一个 (平台, 账号) 记录。
 *
 * 唯一约束 [ukPlatformAccount]:平台名 + 账号字符串判重,导入和编辑时不再需要额外的去重逻辑。
 * `registered_at` 存 `yyyy-MM-dd`,允许 null 表示"我不记得了"。
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(
            value = ["platform", "account"],
            name = "uk_platform_account",
            unique = true,
        ),
        Index(value = ["platform"]),
        Index(value = ["registered_at"]),
        Index(value = ["is_active"]),
    ],
)
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "account_type") val accountType: AccountType,
    @ColumnInfo(name = "registered_at") val registeredAt: String?,
    @ColumnInfo(name = "notes", defaultValue = "") val notes: String,
    /**
     * 账号对应的密码(v0.5.0 新增)。
     *
     * - 可空:用户不一定每个账号都填密码。
     * - **明文存 DB**:沿用现有 notes / registeredAt 等字段的安全契约,只靠 SQLCipher
     *   整库加密。要拿到密码需同时拿到 DB 文件 + DB 解密口令(EncryptedSharedPrefs 里
     *   的 base64)+ EncryptedSharedPrefs master key(AndroidKeyStore 保护),三道防线
     *   叠加,与 notes / registeredAt 攻击面完全相同。
     * - **不**参与唯一约束 / 索引(密码不应该被搜索)。
     * - UI 上默认掩码,详情页点 👁 才显示明文;复制走 SensitiveClipboard(60s 自动清空)。
     */
    @ColumnInfo(name = "password") val password: String?,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 账号类型,存储为字符串 `PHONE` / `EMAIL`,见 [Converters]。 */
enum class AccountType {
    PHONE,
    EMAIL,
}