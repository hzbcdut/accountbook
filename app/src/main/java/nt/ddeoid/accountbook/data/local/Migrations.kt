package nt.ddeoid.accountbook.data.local

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库 schema 迁移集合。
 *
 * 项目第一条 Migration 在 v0.5.0 引入 —— 之前 [AppDatabase] 一直是 v1,无迁移路径。
 * 后续 schema 变更追加到这里,而不是 inline 写在 [DatabaseModule] 里,方便积累。
 */

/**
 * v0.5.0:`accounts` 表新增 `password TEXT`(可空)。
 *
 * - **不加 NOT NULL**:允许"未填密码"的账号。
 * - **不加默认值**:password 本来就该由用户填,旧账号在新 schema 下拿到 SQL 默认 NULL。
 * - **不加索引**:密码不参与搜索。
 *
 * ALTER TABLE 在 SQLCipher 下也走原 SQL,执行期间不破坏整库加密。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN password TEXT")
    }
}

/**
 * 项目当前所有 Migration 的列表,按版本号升序。
 *
 * 接进 [androidx.room.Room.databaseBuilder] 的 `addMigrations(...)` 时,把这个列表
 * 展开传进去即可:
 *
 * ```kotlin
 * Room.databaseBuilder(ctx, AppDatabase::class.java, "accountbook.db")
 *     .addMigrations(*ALL_MIGRATIONS)
 *     .addCallback(ALL_MIGRATIONS_CALLBACK)
 *     ...
 * ```
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
)

/**
 * Migration 触发的诊断 callback。
 *
 * Room 默认在 [RoomDatabase.Callback.onDestructiveMigration] 被调用时**不**自动提示,
 * 但如果在 [androidx.room.Room.databaseBuilder] 上没把对应版本区间的 [Migration] 接
 * 进去,Room 会抛 IllegalStateException("A migration from N to M was required but
 * not found")。挂这个 callback 可以在 onMigrate 完成后打 log,后续 QA / 现场排查时
 * 一眼能看到"已经跑了哪条 Migration"。
 */
val ALL_MIGRATIONS_CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        Log.i(MIGRATIONS_LOG_TAG, "AppDatabase.onCreate:全新建表,版本 = ${AppDatabase::class.java}")
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        Log.i(MIGRATIONS_LOG_TAG, "AppDatabase.onOpen:已打开,版本 = ${db.version}")
    }

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        // 正常情况下不应该走到这里 —— [ALL_MIGRATIONS] 已经覆盖了 1→2。
        // 走到这里说明有迁移路径没接进来,需要补。
        Log.w(MIGRATIONS_LOG_TAG, "AppDatabase.onDestructiveMigration:被触发了!版本 = ${db.version}")
    }
}

/** [ALL_MIGRATIONS_CALLBACK] 用的 log tag,放文件级以便匿名 object 内部访问。 */
private const val MIGRATIONS_LOG_TAG = "Migrations"