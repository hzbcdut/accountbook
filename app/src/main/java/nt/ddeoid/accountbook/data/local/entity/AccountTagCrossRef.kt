package nt.ddeoid.accountbook.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 多对多关联表:一个 Account 可以挂多个 Tag,一个 Tag 可以挂在多个 Account 上。
 * 删除 Account / Tag 时通过 `CASCADE` 自动清理关联行。
 */
@Entity(
    tableName = "account_tag_xref",
    primaryKeys = ["account_id", "tag_id"],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["tag_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AccountTagCrossRef(
    val account_id: String,
    val tag_id: String,
)