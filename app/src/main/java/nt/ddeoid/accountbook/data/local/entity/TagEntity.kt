package nt.ddeoid.accountbook.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签目录。Phase 1 仅 6 个内置,后续用户可在设置/添加时自定义。
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], name = "uk_tag_name", unique = true)],
)
data class TagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** ARGB 颜色,例如 `#FFD0BCFF`。便于 UI chip 直接渲染。 */
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "is_builtin", defaultValue = "0") val isBuiltin: Boolean,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
)