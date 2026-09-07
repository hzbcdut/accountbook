package nt.ddeoid.accountbook.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 平台目录(自动补全数据源)。
 *
 * - `is_custom = 0`:首次启动由种子数据写入,不可删除(隐藏就行)。
 * - `is_custom = 1`:用户在使用中录入过的平台。
 * - `usage_count`:被多少条 Account 引用,自动补全时按这个倒序排。
 */
@Entity(
    tableName = "platform_catalog",
    indices = [
        Index(value = ["name"], name = "uk_platform_name", unique = true),
        Index(value = ["usage_count"]),
    ],
)
data class PlatformCatalogEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "usage_count", defaultValue = "0") val usageCount: Int,
    @ColumnInfo(name = "is_custom", defaultValue = "1") val isCustom: Boolean,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)