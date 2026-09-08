package nt.ddeoid.accountbook.security.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LockPrefs 单元测试 —— 用一个最小化的 [LockPrefs] 子类绕过 Android DataStore,
 * 直接驱动 [Snapshot] 的读写。
 *
 * 走真实 DataStore 需要 Robolectric 或 instrumentation,不在 Phase 4 #28 的范围里。
 * [LockPrefs] 的"业务"集中在 [Snapshot] 解析 + [TimeoutTier] 的 fromMillis 上面,
 * 这两件事正好能用纯 JVM 跑。
 */
class LockPrefsTest {

    @Test
    fun `TimeoutTier fromMillis maps exact values`() {
        assertEquals(LockPrefs.TimeoutTier.IMMEDIATE, LockPrefs.TimeoutTier.fromMillis(0L))
        assertEquals(LockPrefs.TimeoutTier.SHORT, LockPrefs.TimeoutTier.fromMillis(15_000L))
        assertEquals(LockPrefs.TimeoutTier.MEDIUM, LockPrefs.TimeoutTier.fromMillis(60_000L))
        assertEquals(LockPrefs.TimeoutTier.LONG, LockPrefs.TimeoutTier.fromMillis(300_000L))
    }

    @Test
    fun `TimeoutTier fromMillis falls back to IMMEDIATE on unknown`() {
        // Q18=B:不开放自由输入;任何偏离 4 档的值都被当成"立即"。
        // 这条语义很重要:老版本升上来如果残留了某个奇怪的 timeout,默认值
        // 一定是"最严",而不是悄悄放大。
        assertEquals(LockPrefs.TimeoutTier.IMMEDIATE, LockPrefs.TimeoutTier.fromMillis(123L))
        assertEquals(LockPrefs.TimeoutTier.IMMEDIATE, LockPrefs.TimeoutTier.fromMillis(-1L))
    }

    @Test
    fun `Snapshot DEFAULT is locked-off and immediate timeout`() {
        val s = LockPrefs.Snapshot.DEFAULT
        assertFalse(s.lockEnabled)
        assertFalse("wizardCompleted 默认 false,代表从未走过 wizard", s.wizardCompleted)
        assertEquals(LockPrefs.TimeoutTier.IMMEDIATE.millis, s.timeoutMs)
        assertEquals(0L, s.lastBackgroundedAt)
    }

    @Test
    fun `Tier labels are non-empty and tier count is exactly four`() {
        // Q18=B:4 档固定。这是写死的契约 —— 测试要保住它,免得有人随手加一档。
        val tiers = LockPrefs.TimeoutTier.entries
        assertEquals(4, tiers.size)
        tiers.forEach { assertTrue("label for $it must be non-empty", it.label.isNotBlank()) }
        // 4 档之间必须互不相同。
        assertEquals(tiers.size, tiers.map { it.millis }.toSet().size)
    }
}
