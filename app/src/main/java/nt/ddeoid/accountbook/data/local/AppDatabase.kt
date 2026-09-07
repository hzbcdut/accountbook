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
 * - 版本 1,Phase 1 仅建表 + 写种子;后续 Phase 6 测试/迁移会再升版本。
 * - 通过 SQLCipher 在 [DatabaseModule] 里整体加密。
 */
@Database(
    entities = [
        AccountEntity::class,
        AccountTagCrossRef::class,
        PlatformCatalogEntity::class,
        TagEntity::class,
    ],
    version = 1,
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