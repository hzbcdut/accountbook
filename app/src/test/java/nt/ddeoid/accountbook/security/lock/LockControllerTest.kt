package nt.ddeoid.accountbook.security.lock

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import nt.ddeoid.accountbook.data.local.DatabasePassphraseProvider
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.MigrationMarker
import nt.ddeoid.accountbook.security.crypto.MnemonicException
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [LockController] 状态机测试 —— Phase 4 #31 新增的部分:
 *
 * - `attemptUnlockWithPin` 冷却 + 失败计数
 * - `skipSetup` 走 Disabled 路径
 * - `bootstrap` 四态分发(Normal / Migrating / NeedsSetup / Disabled / Locked)
 *
 * 用 mockk 替掉所有副作用依赖([DatabaseProvider] / [KeyVault] / [LockPrefs] / 等等),
 * 因为 [LockPrefs] 依赖 Android DataStore,在 JVM 单测里跑不动 —— 但我们要测的是
 * controller 的**逻辑**,prefs 的 IO 是次要的。
 */
class LockControllerTest {

    private lateinit var databaseProvider: DatabaseProvider
    private lateinit var keyVault: KeyVault
    private lateinit var lockPrefs: LockPrefs
    private lateinit var mnemonicCodec: MnemonicCodec
    private lateinit var passphraseProvider: DatabasePassphraseProvider
    private lateinit var migrationMarker: MigrationMarker
    private lateinit var controller: LockController

    @Before
    fun setUp() {
        databaseProvider = mockk(relaxed = true)
        keyVault = mockk(relaxed = true)
        lockPrefs = mockk(relaxed = true)
        mnemonicCodec = mockk(relaxed = true)
        passphraseProvider = mockk(relaxed = true)
        migrationMarker = mockk(relaxed = true)
        // 默认 snapshot = DEFAULT,方便大部分测试直接走"全新设备"路径
        coEvery { lockPrefs.snapshot() } returns LockPrefs.Snapshot.DEFAULT
        every { migrationMarker.inProgress } returns false
        every { keyVault.isInitialized() } returns false
        every { passphraseProvider.hasLegacy() } returns false
        controller = LockController(
            databaseProvider = databaseProvider,
            keyVault = keyVault,
            lockPrefs = lockPrefs,
            mnemonicCodec = mnemonicCodec,
            passphraseProvider = passphraseProvider,
            migrationMarker = migrationMarker,
        )
    }

    // --- bootstrap 四态分发 ---------------------------------------------

    @Test
    fun `bootstrap fresh install goes to NeedsSetup`() = runTest {
        controller.bootstrap()
        assertEquals(LockController.LockState.NeedsSetup, controller.state.value)
    }

    @Test
    fun `bootstrap with migration interrupted goes to Migrating`() = runTest {
        every { migrationMarker.inProgress } returns true
        controller.bootstrap()
        assertEquals(LockController.LockState.Migrating, controller.state.value)
    }

    @Test
    fun `bootstrap with legacy passphrase and no vault goes to Migrating`() = runTest {
        every { keyVault.isInitialized() } returns false
        every { passphraseProvider.hasLegacy() } returns true
        controller.bootstrap()
        assertEquals(LockController.LockState.Migrating, controller.state.value)
    }

    @Test
    fun `bootstrap with vault initialized and lockEnabled goes to Locked`() = runTest {
        every { keyVault.isInitialized() } returns true
        coEvery { lockPrefs.snapshot() } returns LockPrefs.Snapshot.DEFAULT.copy(
            lockEnabled = true,
            wizardCompleted = true,
        )
        controller.bootstrap()
        assertEquals(LockController.LockState.Locked, controller.state.value)
    }

    @Test
    fun `bootstrap with wizardCompleted and lockEnabled false goes to Disabled`() = runTest {
        // 用户在 wizard 选了 Skip,KeyVault 没初始化过 → Disabled
        every { keyVault.isInitialized() } returns false
        coEvery { lockPrefs.snapshot() } returns LockPrefs.Snapshot.DEFAULT.copy(
            lockEnabled = false,
            wizardCompleted = true,
        )
        controller.bootstrap()
        assertEquals(LockController.LockState.Disabled, controller.state.value)
    }

    // --- skipSetup ------------------------------------------------------

    @Test
    fun `skipSetup from NeedsSetup goes to Disabled and writes prefs`() = runTest {
        controller.bootstrap()   // → NeedsSetup

        controller.skipSetup()

        assertEquals(LockController.LockState.Disabled, controller.state.value)
        coVerifyOrder {
            lockPrefs.setLockEnabled(false)
            lockPrefs.setWizardCompleted(true)
        }
        // 关键:skip 不应打开 DB
        coVerify(exactly = 0) { databaseProvider.open(any()) }
    }

    @Test
    fun `skipSetup from Disabled throws`() = runTest {
        // bootstrap → NeedsSetup,然后 skip → Disabled。第二次 skip 应拒绝。
        controller.bootstrap()
        controller.skipSetup()
        try {
            controller.skipSetup()
            fail("Disabled 状态下 skipSetup 应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    // --- attemptUnlockWithPin 冷却 --------------------------------------

    @Test
    fun `attemptUnlockWithPin success keeps no cooldown`() = runTest {
        bootstrapAsLocked()
        // 用真 MasterKeyHandle(entropy 是 16 字节)—— mockk relaxed 会让 entropy=null,
        // 后面 MasterKeyFactory.fromEntropy 会 NPE。
        val realHandle = KeyVault.MasterKeyHandle(
            ByteArray(16) { it.toByte() },
            KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN,
        )
        coEvery { keyVault.unlockWithPin(any()) } returns realHandle
        every { databaseProvider.open(any()) } returns mockk(relaxed = true)

        val pin = "123456".toCharArray()
        val result = controller.attemptUnlockWithPin(pin)
        try {
            assertTrue(result.isSuccess)
            assertEquals(0L, controller.cooldownUntilEpochMs)
            assertEquals(LockController.LockState.Unlocked, controller.state.value)
        } finally {
            // 不需要 wipe pin —— controller 内部没用它存到任何字段
        }
    }

    @Test
    fun `5 wrong pins trigger 30s cooldown`() = runTest {
        bootstrapAsLocked()
        coEvery { keyVault.unlockWithPin(any()) } throws RuntimeException("wrong")

        repeat(5) {
            val r = controller.attemptUnlockWithPin("wrong".toCharArray())
            assertTrue("第 ${it + 1} 次错误不应抛 InCooldown", r.isFailure)
            assertFalse(
                "前 4 次错误不应进冷却,实际 cooldownUntil=${controller.cooldownUntilEpochMs}",
                r.exceptionOrNull() is LockException.InCooldown,
            )
        }
        // 第 5 次错误后:cooldownUntilEpochMs > now
        val now = System.currentTimeMillis()
        assertTrue(
            "5 次错误后 cooldownUntil 应在未来 ~30s; 实际 now=$now, cd=${controller.cooldownUntilEpochMs}",
            controller.cooldownUntilEpochMs in (now + 29_000)..(now + 31_000),
        )
    }

    @Test
    fun `cooldown blocks subsequent attempts`() = runTest {
        bootstrapAsLocked()
        coEvery { keyVault.unlockWithPin(any()) } throws RuntimeException("wrong")

        repeat(5) { controller.attemptUnlockWithPin("wrong".toCharArray()) }
        // 现在冷却中
        val r = controller.attemptUnlockWithPin("wrong".toCharArray())
        assertTrue(r.isFailure)
        val ex = r.exceptionOrNull()
        assertTrue("冷却期应抛 InCooldown,实际 $ex", ex is LockException.InCooldown)
    }

    @Test
    fun `10 wrong pins trigger 60s cooldown`() = runTest {
        bootstrapAsLocked()
        coEvery { keyVault.unlockWithPin(any()) } throws RuntimeException("wrong")

        // 在 attempts 之间清空 cooldown(但保留 failedAttempts),模拟"时间过去"——
        // 否则第 6 次开始被冷却拦截,failedAttempts 永远到不了 10。
        repeat(10) {
            controller.clearCooldownForTesting()
            controller.attemptUnlockWithPin("wrong".toCharArray())
        }
        val now = System.currentTimeMillis()
        assertTrue(
            "10 次错误后 cooldownUntil 应在未来 ~60s; 实际 now=$now, cd=${controller.cooldownUntilEpochMs}",
            controller.cooldownUntilEpochMs in (now + 59_000)..(now + 61_000),
        )
    }

    @Test
    fun `cooldown caps at 5min after 25 wrong pins`() = runTest {
        bootstrapAsLocked()
        coEvery { keyVault.unlockWithPin(any()) } throws RuntimeException("wrong")

        repeat(25) {
            controller.clearCooldownForTesting()
            controller.attemptUnlockWithPin("wrong".toCharArray())
        }
        val now = System.currentTimeMillis()
        // tier = (25-5)/5 = 4,30 << 4 = 480 → cap 到 300s
        assertTrue(
            "25 次错误后 cooldownUntil 应在未来 300s ±1; 实际 now=$now, cd=${controller.cooldownUntilEpochMs}",
            controller.cooldownUntilEpochMs in (now + 299_000)..(now + 301_000),
        )
    }

    @Test
    fun `success after failures resets cooldown`() = runTest {
        bootstrapAsLocked()
        // 前 4 次错(冷却还没触发)
        coEvery { keyVault.unlockWithPin(any()) } throws RuntimeException("wrong")
        repeat(4) { controller.attemptUnlockWithPin("wrong".toCharArray()) }
        // 第 5 次对
        val realHandle = KeyVault.MasterKeyHandle(
            ByteArray(16) { it.toByte() },
            KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN,
        )
        coEvery { keyVault.unlockWithPin(any()) } returns realHandle
        every { databaseProvider.open(any()) } returns mockk(relaxed = true)
        val r = controller.attemptUnlockWithPin("right".toCharArray())
        assertTrue(r.isSuccess)
        assertEquals(0L, controller.cooldownUntilEpochMs)
        // 成功 → state=Unlocked
        assertEquals(LockController.LockState.Unlocked, controller.state.value)
    }

    // --- 工具 ------------------------------------------------------------

    private suspend fun bootstrapAsLocked() {
        every { keyVault.isInitialized() } returns true
        coEvery { lockPrefs.snapshot() } returns LockPrefs.Snapshot.DEFAULT.copy(
            lockEnabled = true,
            wizardCompleted = true,
        )
        controller.bootstrap()
        assertEquals(LockController.LockState.Locked, controller.state.value)
    }
}
