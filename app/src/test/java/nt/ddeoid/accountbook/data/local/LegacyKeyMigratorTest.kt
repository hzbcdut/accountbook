package nt.ddeoid.accountbook.data.local

import io.mockk.Runs
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import nt.ddeoid.accountbook.security.crypto.EntropySource
import nt.ddeoid.accountbook.security.crypto.MasterKeyFactory
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.lock.KeyVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * [LegacyKeyMigrator] 的编排测试 —— 验证:
 *
 * - 前置条件不满足时抛 [IllegalStateException]
 * - 步骤顺序:marker.on → rekey → vault.init → legacy.wipe → marker.off
 * - marker.inProgress 始终在 try/finally 范围内被清理(即使某一步抛)
 *
 * 不测真实的 PRAGMA rekey 语义 —— 那是 instrumented test 的事,这里只保证编排对。
 *
 * [MasterKeyFactory.generate] 走真实实现,因为它只是 CPU 计算;其他依赖都 mock。
 */
class LegacyKeyMigratorTest {

    private lateinit var codec: MnemonicCodec
    private lateinit var passphraseProvider: DatabasePassphraseProvider
    private lateinit var rekeyer: DatabaseRekeyer
    private lateinit var keyVault: KeyVault
    private lateinit var marker: MigrationMarker
    private lateinit var migrator: LegacyKeyMigrator
    private lateinit var entropySource: EntropySource

    @Before
    fun setUp() {
        val raw = checkNotNull(javaClass.classLoader!!.getResourceAsStream("bip39-english.txt")) {
            "测试资源里找不到 bip39-english.txt"
        }.readBytes()
        codec = MnemonicCodec(nt.ddeoid.accountbook.security.crypto.Bip39WordList.parseAndVerify(raw))
        passphraseProvider = mockk(relaxed = true)
        rekeyer = mockk(relaxed = true)
        keyVault = mockk(relaxed = true)
        marker = mockk(relaxed = true)
        entropySource = EntropySource()
        migrator = LegacyKeyMigrator(
            passphraseProvider = passphraseProvider,
            rekeyer = rekeyer,
            keyVault = keyVault,
            migrationMarker = marker,
            entropySource = entropySource,
        )
    }

    @Test
    fun `migrate refuses when no legacy passphrase`() = runTest {
        every { passphraseProvider.hasLegacy() } returns false
        try {
            migrator.migrate("123456".toCharArray(), codec)
            fail("没有 legacy 口令应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    @Test
    fun `migrate refuses when marker already inProgress`() = runTest {
        every { passphraseProvider.hasLegacy() } returns true
        every { keyVault.isInitialized() } returns false
        every { marker.inProgress } returns true
        try {
            migrator.migrate("123456".toCharArray(), codec)
            fail("marker 已 inProgress 应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    @Test
    fun `migrate refuses when KeyVault already initialized`() = runTest {
        every { passphraseProvider.hasLegacy() } returns true
        every { keyVault.isInitialized() } returns true
        try {
            migrator.migrate("123456".toCharArray(), codec)
            fail("KeyVault 已初始化应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    @Test
    fun `happy path runs steps in order`() = runTest {
        every { passphraseProvider.hasLegacy() } returns true
        every { keyVault.isInitialized() } returns false
        every { marker.inProgress } returns false andThen true
        every { passphraseProvider.getOrCreate() } returns ByteArray(32).also { SecureRandom().nextBytes(it) }
        every { keyVault.initializeWithExistingEntropy(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { passphraseProvider.wipe() } just Runs
        every { marker.inProgress = false } returns Unit

        migrator.migrate("abcdefgh".toCharArray(), codec)

        // 步骤顺序:marker.on → rekey → vault.init → legacy.wipe
        coVerifyOrder {
            rekeyer.rekey(any(), any())
            // 迁移路径**不**启用生物识别(Bug #38 修复回归):用户必须先在系统里确认
            // 新设备有 secure lock screen。false 显式锁住这个契约。
            keyVault.initializeWithExistingEntropy(any(), any(), codec, false)
            passphraseProvider.wipe()
        }
        // marker:on → off 都要被调到
        verifyOrder {
            marker.inProgress = true
            marker.inProgress = false
        }
    }

    @Test
    fun `marker cleared even when rekey throws`() = runTest {
        every { passphraseProvider.hasLegacy() } returns true
        every { keyVault.isInitialized() } returns false
        every { marker.inProgress } returns false andThen true
        every { passphraseProvider.getOrCreate() } returns ByteArray(32).also { SecureRandom().nextBytes(it) }
        every { rekeyer.rekey(any(), any()) } throws RuntimeException("模拟 rekey 失败")

        try {
            migrator.migrate("123456".toCharArray(), codec)
            fail("rekey 抛异常应当传播")
        } catch (e: RuntimeException) {
            assertTrue("异常应当是 rekey 那个", e.message?.contains("rekey") == true)
        }
        // 关键:即使 rekey 失败,marker 也得被清掉 —— 否则下次启动进死循环
        verifyOrder {
            marker.inProgress = true
            marker.inProgress = false
        }
    }

    @Test
    fun `marker cleared even when KeyVault init throws`() = runTest {
        every { passphraseProvider.hasLegacy() } returns true
        every { keyVault.isInitialized() } returns false
        every { marker.inProgress } returns false andThen true
        every { passphraseProvider.getOrCreate() } returns ByteArray(32).also { SecureRandom().nextBytes(it) }
        every { keyVault.initializeWithExistingEntropy(any(), any(), any(), any()) } throws RuntimeException("vault 炸了")

        try {
            migrator.migrate("123456".toCharArray(), codec)
            fail("vault init 抛异常应当传播")
        } catch (e: RuntimeException) {
            // 期望
        }
        verifyOrder {
            marker.inProgress = true
            marker.inProgress = false
        }
    }
}
