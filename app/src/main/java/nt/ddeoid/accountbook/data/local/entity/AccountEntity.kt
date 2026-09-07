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
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 账号类型,存储为字符串 `PHONE` / `EMAIL`,见 [Converters]。 */
enum class AccountType {
    PHONE,
    EMAIL,
}