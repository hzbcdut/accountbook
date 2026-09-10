package nt.ddeoid.accountbook.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * [MIGRATION_1_2] 单测。
 *
 * 纯 JVM 单测:不真跑 SQLite,只验证 `migrate()` 触发的 SQL 形态正确。
 * 真实 SQLite 升级由 instrumented test 覆盖(项目目前不跑 instrumented test,
 * 见 plan 的 Verification 段)。
 *
 * 验证两件事:
 * - 1. 走的是 ALTER TABLE,不是别的 DDL
 * - 2. 列名 = `password`,类型 = `TEXT`,**没有 NOT NULL / 没有 DEFAULT**
 */
class MigrationsTest {

    @Test
    fun `MIGRATION_1_2 adds password column with TEXT affinity and no constraints`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_1_2.migrate(db)

        // 精确匹配 ALTER TABLE 语句;不要带引号、不要带 NOT NULL、不要带 DEFAULT
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN password TEXT")
        }
    }

    @Test
    fun `ALL_MIGRATIONS contains MIGRATION_1_2`() {
        // 防止有人改了 ALL_MIGRATIONS 但忘了把 MIGRATION_1_2 加进去 —— 这种情况下
        // 上线时会触发 IllegalStateException("A migration from 1 to 2 was required but
        // not found"),现场难复现。这条测试给个早期警告。
        assert(ALL_MIGRATIONS.contains(MIGRATION_1_2)) {
            "ALL_MIGRATIONS 必须包含 MIGRATION_1_2;当前 = ${ALL_MIGRATIONS.toList()}"
        }
        // 还应该只有一个元素(避免有重复 / 错放)
        assert(ALL_MIGRATIONS.size == 1) {
            "ALL_MIGRATIONS 当前只有一个 MIGRATION_1_2;有多个说明有版本漏接或重复注册"
        }
    }
}