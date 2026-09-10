package nt.ddeoid.accountbook.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.dao.TagDao
import nt.ddeoid.accountbook.data.local.entity.AccountEntity
import nt.ddeoid.accountbook.data.local.entity.AccountTagCrossRef
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity
import nt.ddeoid.accountbook.data.local.entity.TagEntity

/**
 * AccountBook 主数据库。
 *
 * - 当前版本 2。Schema 变更历史:
 *   - v1 → v2(v0.5.0):`accounts` 表新增 `password TEXT` 列(可空)。
 *     见 [MIGRATION_1_2]。
 * - 通过 SQLCipher 在 [DatabaseModule] 里整体加密。
 */
@Database(
    entities = [
        AccountEntity::class,
        AccountTagCrossRef::class,
        PlatformCatalogEntity::class,
        TagEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun platformCatalogDao(): PlatformCatalogDao
    abstract fun tagDao(): TagDao

    companion object {
        const val DATABASE_NAME: String = "accountbook.db"
    }
}