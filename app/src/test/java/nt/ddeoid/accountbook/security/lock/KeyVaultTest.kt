package nt.ddeoid.accountbook.security.lock

import android.content.SharedPreferences
import nt.ddeoid.accountbook.security.crypto.Bip39WordList
import nt.ddeoid.accountbook.security.crypto.CryptoException
import nt.ddeoid.accountbook.security.crypto.EntropySource
import nt.ddeoid.accountbook.security.crypto.KeyWrapper
import nt.ddeoid.accountbook.security.crypto.MasterKeyFactory
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.PinKdf
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * [KeyVault] 的单元测试。
 *
 * 两个真东西:Android Keystore 和 EncryptedSharedPreferences。两者都用 [FakeKeystoreAccess]
 * 和一个直白的 in-memory prefs 替掉 —— 这样所有 wrap/unwrap 逻辑都在 JVM 里跑,
 * 不需要 instrumentation。
 *
 * ⚠️ 不验证 Keystore 本身的安全性(那是系统的事),只验证 KeyVault 的状态机 +
 * "PIN 路径解出的熵 == 原始熵" + "PIN 路径解生物识别 blob 必失败" 这类**自己代码
 * 负责**的语义。
 */
class KeyVaultTest {

    private lateinit var codec: MnemonicCodec
    private lateinit var prefs: InMemoryPrefs
    private lateinit var keystore: FakeKeystoreAccess
    private lateinit var vault: KeyVault

    @Before
    fun setUp() {
        val raw = checkNotNull(javaClass.classLoader!!.getResourceAsStream("bip39-english.txt")) {
            "测试资源里找不到 bip39-english.txt"
        }.readBytes()
        val words = Bip39WordList.parseAndVerify(raw)
        codec = MnemonicCodec(words)
        prefs = InMemoryPrefs()
        keystore = FakeKeystoreAccess()
        vault = KeyVault(
            prefsFactory = prefs,
            keystoreAccess = keystore,
            entropySource = EntropySource(),
            wrapper = KeyWrapper(EntropySource()),
            pinKdfIterations = 1000, // 单测跑小值,生产走 PRODUCTION_ITERATIONS
        )
    }

    // --- initialize / unlockWithPin round-trip -----------------------------

    @Test
    fun `initialize writes entropy and both blobs`() {
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = true)
        try {
            assertTrue(vault.isInitialized())
            assertTrue(vault.hasPin())
            assertTrue(vault.hasBiometric())
            assertEquals(12, setup.mnemonic.size)
            assertEquals(setup.masterKey.bytes.size, MasterKeyFactory.LENGTH_BYTES)
        } finally {
            SecretBytes.wipe(pin)
            setup.masterKey.wipe()
        }
    }

    @Test
    fun `unlockWithPin recovers the original entropy bytes`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        val originalEntropy = readEntropyFromPrefs()

        val pin2 = "123456".toCharArray()
        val handle = try {
            vault.unlockWithPin(pin2)
        } finally {
            SecretBytes.wipe(pin2)
        }
        try {
            assertArrayEquals(originalEntropy, handle.entropy)
            assertEquals(KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN, handle.kind)
        } finally {
            handle.wipe()
            originalEntropy.fill(0)
        }
    }

    @Test
    fun `initialize returns masterKey derived from real entropy not zeros`() {
        // 回归:Bug #38 端到端验证时发现的 entropy.fill(0) 提前擦除 bug。
        // [initialize] 返回的 masterKey 必须能跟 [unlockWithPin] 之后再 derive
        // 出来的 masterKey 对齐 —— 否则 setup 时打开的 DB 跟 unlock 时打开的 DB
        // 用了不同的 key,SQLCipher 报 "file is not a database"。
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        try {
            val expected = MasterKeyFactory.fromEntropy(readEntropyFromPrefs()).bytes
            assertArrayEquals(expected, setup.masterKey.bytes)
        } finally {
            setup.masterKey.wipe()
        }
    }

    @Test
    fun `unlockWithPin fails with wrong pin`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)

        val wrong = "999999".toCharArray()
        try {
            vault.unlockWithPin(wrong)
            fail("应当抛 UnwrapFailed")
        } catch (e: CryptoException.UnwrapFailed) {
            // 期望路径:错的 PIN → 派生密钥对不上 → AES-GCM tag 校验失败 → UnwrapFailed。
        } finally {
            SecretBytes.wipe(wrong)
        }
    }

    // --- biometric 与 PIN 路径独立(Q1=A) ---------------------------------

    @Test
    fun `PIN path cannot unwrap biometric blob`() {
        // 这条测试保住 [KeyWrapper.WrapContext] 的 AAD 真的起到了分桶作用。
        // 如果谁不小心把 WrapContext.PIN / BIOMETRIC 改成共享 AAD,这条会红。
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)

        val bioBlob = prefs.getString("kv.bio_blob", null)
        val salt = prefs.getString("kv.pin_salt", null)
        assertNotNull(bioBlob); assertNotNull(salt)
        val pinKey = PinKdf.derive(
            "123456".toCharArray(),
            java.util.Base64.getDecoder().decode(salt),
            1000,
        )
        try {
            try {
                KeyWrapper(EntropySource()).unwrap(
                    pinKey.bytes, bioBlob!!, KeyWrapper.WrapContext.PIN,
                )
                fail("PIN 路径不应能解生物识别 blob")
            } catch (e: CryptoException.UnwrapFailed) {
                // 期望路径
            }
        } finally {
            pinKey.wipe()
        }
    }

    @Test
    fun `biometric unlock yields the same entropy`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        val originalEntropy = readEntropyFromPrefs()

        val handle = vault.unlockWithBiometric()
        try {
            assertArrayEquals(originalEntropy, handle.entropy)
            assertEquals(KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_BIOMETRIC, handle.kind)
        } finally {
            handle.wipe()
            originalEntropy.fill(0)
        }
    }

    // --- 助记词恢复 -------------------------------------------------------

    @Test
    fun `recoverFromMnemonic round-trips through 12 words`() {
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = true)
        val mnemonic = setup.mnemonic
        SecretBytes.wipe(pin)
        setup.masterKey.wipe()
        val originalEntropy = readEntropyFromPrefs()

        val recovered = vault.recoverFromMnemonic(mnemonic, codec)
        try {
            assertArrayEquals(originalEntropy, recovered.entropy)
            assertEquals(KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_MNEMONIC, recovered.kind)
        } finally {
            recovered.wipe()
            originalEntropy.fill(0)
        }
    }

    @Test
    fun `recoverFromMnemonic fails on bad checksum`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)

        // 构造一个有**故意错配校验位**的助记词。
        //
        // 直接换词有 1/16 概率凑巧让校验位仍然有效 —— 旧版本这么写就偶发失败。
        // 这里用"前 11 词来自 sister 熵(byte 0 XOR 0x01)+ 第 12 词保留 good"的方法构造,
        // 然后用 codec 预校验:必须是 ChecksumMismatch 才用来测 KeyVault,否则换熵重试。
        //
        // 100 次重试凑不出一组坏校验位的概率 < (1/16)^100 ≈ 0 —— 测试从概率确定变成**实际**确定。
        val tampered = buildBadChecksumMnemonic()

        try {
            vault.recoverFromMnemonic(tampered, codec)
            fail("应抛 ChecksumMismatch")
        } catch (e: nt.ddeoid.accountbook.security.crypto.MnemonicException.ChecksumMismatch) {
            // 期望路径
        }
    }

    private fun buildBadChecksumMnemonic(): List<String> {
        repeat(100) {
            val goodEntropy = EntropySource().nextBytes(16)
            // XOR bit 0 of byte 0(在 word 0 的 bits 0-10 范围内)。这样 sister 熵
            // 只在 bit 0 上与 good 不同 → SHA256 大概率前 4 bit 也不同 →
            // 用 goodMnemonic[11] 当校验位词会跟 SHA256(sister 部分) 对不上。
            val sisterEntropy = goodEntropy.copyOf().also {
                it[0] = (it[0].toInt() xor 0x01).toByte()
            }
            val goodMnemonic = codec.encode(goodEntropy)
            val sisterMnemonic = codec.encode(sisterEntropy)
            val candidate = (sisterMnemonic.take(11) + goodMnemonic[11])
            try {
                codec.decode(candidate)
                // 候选 mnemonic 校验位居然有效 —— 跳过
            } catch (e: nt.ddeoid.accountbook.security.crypto.MnemonicException.ChecksumMismatch) {
                return candidate
            }
        }
        error("100 次重试都没构造出坏校验位助记词 —— 概率上不可能,大概是 EntropySource 出问题")
    }

    // --- changePin --------------------------------------------------------

    @Test
    fun `changePin keeps entropy and replaces blob`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        val originalEntropy = readEntropyFromPrefs()

        val oldPin = "123456".toCharArray()
        val newPin = "abcdefgh".toCharArray()
        vault.changePin(oldPin, newPin)
        SecretBytes.wipe(oldPin)
        SecretBytes.wipe(newPin)

        val newHandle = vault.unlockWithPin("abcdefgh".toCharArray())
        try {
            assertArrayEquals(originalEntropy, newHandle.entropy)
        } finally {
            newHandle.wipe()
            originalEntropy.fill(0)
        }
        // 老 PIN 已经解不出来了
        try {
            vault.unlockWithPin("123456".toCharArray())
            fail("老 PIN 应当失效")
        } catch (e: CryptoException.UnwrapFailed) {
            // 期望
        }
    }

    @Test
    fun `changePin fails when old pin is wrong`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)

        val oldPin = "000000".toCharArray()
        val newPin = "abcdefgh".toCharArray()
        try {
            vault.changePin(oldPin, newPin)
            fail("老 PIN 错应当抛 UnwrapFailed")
        } catch (e: CryptoException.UnwrapFailed) {
            // 期望
        } finally {
            SecretBytes.wipe(oldPin)
            SecretBytes.wipe(newPin)
        }
    }

    // --- wipe / removeXxx -------------------------------------------------

    @Test
    fun `wipe removes everything`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        assertTrue(vault.isInitialized())

        vault.wipe()
        assertFalse(vault.isInitialized())
        assertFalse(vault.hasPin())
        assertFalse(vault.hasBiometric())
    }

    @Test
    fun `removePinPath keeps biometric`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        vault.removePinPath()
        assertFalse(vault.hasPin())
        assertTrue(vault.hasBiometric())
        assertTrue(vault.isInitialized())
    }

    @Test
    fun `removeBiometricPath keeps PIN`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        vault.removeBiometricPath()
        assertTrue(vault.hasPin())
        assertFalse(vault.hasBiometric())
    }

    @Test
    fun `reenrollBiometric rotates the alias and keeps entropy`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        val originalEntropy = readEntropyFromPrefs()

        vault.reenrollBiometric()

        val handle = vault.unlockWithBiometric()
        try {
            assertArrayEquals(originalEntropy, handle.entropy)
        } finally {
            handle.wipe()
            originalEntropy.fill(0)
        }
    }

    // --- 二次初始化保护 ---------------------------------------------------

    @Test
    fun `initialize twice throws`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        val pin2 = "abcdefgh".toCharArray()
        try {
            vault.initialize(pin2, codec, biometricEnabled = true)
            fail("重复 initialize 应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        } finally {
            SecretBytes.wipe(pin2)
        }
    }

    @Test
    fun `unlockWithBiometric without biometric blob throws`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        vault.removeBiometricPath()
        try {
            vault.unlockWithBiometric()
            fail("没生物识别路径应当抛 BlobCorrupted")
        } catch (e: CryptoException.BlobCorrupted) {
            // 期望
        }
    }

    // --- biometricEnabled 参数 (v0.4.2 Bug #38 修复回归) -------------

    /**
     * Bug #38 回归:wizard 步骤 4 上,如果用户**没勾**「启用生物识别」,setup 应当**不调用**
     * Keystore 创建密钥 —— 既不写 bio blob 也不留 Keystore 密钥。
     *
     * 之前 `initialize` 无条件调 `ensureBiometricKey()`,在没 secure lock screen 的设备上
     * 直接抛 `CryptoException.BlobCorrupted` → 整个 wizard 协程崩溃。
     */
    @Test
    fun `initialize with biometricEnabled false skips biometric path entirely`() {
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = false)
        try {
            assertTrue(vault.isInitialized())
            assertTrue(vault.hasPin())
            assertFalse("biometricEnabled=false 时 hasBiometric() 应当为 false", vault.hasBiometric())
            // 没有触发 Keystore 密钥创建(alias 应当是初始空值)
            assertEquals("", keystore.lastIssuedAlias)
        } finally {
            SecretBytes.wipe(pin)
            setup.masterKey.wipe()
        }
    }

    /**
     * Bug #38 核心回归:用户**勾了**生物识别,但设备没有 secure lock screen / Keystore
     * 不可用 —— setup 必须**完成**(有熵 + 有 PIN blob),而不是把整个协程崩了。
     * bio blob 不写,hasBiometric()==false,后续可在 Settings 通过 enableBiometric 重试。
     */
    @Test
    fun `initialize with biometricEnabled true but Keystore failing skips bio blob and completes`() {
        keystore.shouldThrowOnEnsureBiometricKey = true
        val pin = "123456".toCharArray()
        val setup = try {
            vault.initialize(pin, codec, biometricEnabled = true)
        } finally {
            SecretBytes.wipe(pin)
        }
        try {
            assertTrue("Keystore 失败时 setup 仍应完成", vault.isInitialized())
            assertTrue(vault.hasPin())
            assertFalse(
                "Keystore 失败时 bio blob 不写,hasBiometric() 应当为 false",
                vault.hasBiometric(),
            )
            assertEquals(12, setup.mnemonic.size)
        } finally {
            setup.masterKey.wipe()
        }
    }

    // --- biometric 异常吞咽(v0.4.2 Bug #47 修复回归)-----------------

    /**
     * Bug #47 回归 v0.4.2:放宽 `KeyVault.initialize*` 中 `ensureBiometricKey()`
     * 的 catch,从 `CryptoException.BlobCorrupted` 改成 `Throwable`。理由是
     * biometric 路径整体是 **best-effort** —— emulator 无 secure lock screen、
     * Keystore 抛 `IllegalStateException`、StrongBox 不可用、binder 死锁、
     * OEM 定制 Keystore 抛自定义异常等,都不应当让用户的 PIN setup 失败。
     *
     * 这条用例模拟"非 BlobCorrupted 的任意 Throwable"(e.g. `KeyStoreException`、
     * `IllegalStateException`、OEM 自定义),验证 setup 仍正常完成。
     */
    @Test
    fun `initialize biometric path swallows arbitrary Throwable and completes`() {
        // 切到一个专门抛非 BlobCorrupted 的子类
        keystore.customThrowableToThrow = java.lang.IllegalStateException(
            "OEM Keystore binder 死锁 / 模拟强 box 不可用",
        )
        val pin = "123456".toCharArray()
        val setup = try {
            vault.initialize(pin, codec, biometricEnabled = true)
        } finally {
            SecretBytes.wipe(pin)
        }
        try {
            assertTrue("任意 Throwable 都应被吞下,setup 仍完成", vault.isInitialized())
            assertTrue(vault.hasPin())
            assertFalse(
                "Keystore 失败时 bio blob 不写,hasBiometric() 应当为 false",
                vault.hasBiometric(),
            )
            assertEquals(12, setup.mnemonic.size)
        } finally {
            setup.masterKey.wipe()
        }
    }

    /**
     * Bug #47 回归 v0.4.2:原来的行为 —— `BlobCorrupted` 被吞下 —— 必须保留。
     * (这条本来就被 `initialize with biometricEnabled true but Keystore failing
     * skips bio blob and completes` 覆盖,但这里再写一遍,明确"BlobCorrupted 这一
     * 类型仍在 catch 范围内"的不变量。)
     */
    @Test
    fun `initialize biometric path swallows BlobCorrupted and completes`() {
        keystore.shouldThrowOnEnsureBiometricKey = true
        val pin = "123456".toCharArray()
        val setup = try {
            vault.initialize(pin, codec, biometricEnabled = true)
        } finally {
            SecretBytes.wipe(pin)
        }
        try {
            assertTrue(vault.isInitialized())
            assertTrue(vault.hasPin())
            assertFalse(vault.hasBiometric())
        } finally {
            setup.masterKey.wipe()
        }
    }

    // ---------------------------------------------------------------------

    @Test
    fun `initializeWithExistingEntropy with biometricEnabled false skips bio blob`() {
        // 迁移路径(LegacyKeyMigrator)走的就是这个分支。
        val entropy = ByteArray(16) { (it + 7).toByte() }
        val pin = "123456".toCharArray()
        vault.initializeWithExistingEntropy(entropy, pin, codec, biometricEnabled = false)
        try {
            assertTrue(vault.isInitialized())
            assertTrue(vault.hasPin())
            assertFalse(vault.hasBiometric())
        } finally {
            SecretBytes.wipe(pin)
            entropy.fill(0)
        }
    }

    // --- enableBiometric (v0.4.2 新增,给未来 Settings UI 用) -------------

    @Test
    fun `enableBiometric writes bio blob when Keystore succeeds`() {
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = false)
        SecretBytes.wipe(pin)
        setup.masterKey.wipe()

        assertFalse(vault.hasBiometric())
        val r = vault.enableBiometric()
        try {
            assertTrue("enableBiometric 应当成功: $r", r.isSuccess)
            assertTrue(vault.hasBiometric())
            // 新写的 bio blob 能解
            val handle = vault.unlockWithBiometric()
            handle.wipe()
        } catch (t: Throwable) {
            throw t
        }
    }

    @Test
    fun `enableBiometric returns failure when Keystore unavailable`() {
        val pin = "123456".toCharArray()
        val setup = vault.initialize(pin, codec, biometricEnabled = false)
        SecretBytes.wipe(pin)
        setup.masterKey.wipe()

        keystore.shouldThrowOnEnsureBiometricKey = true
        val r = vault.enableBiometric()
        assertTrue("Keystore 失败时 enableBiometric 应当返回 failure", r.isFailure)
        assertFalse(vault.hasBiometric())
    }

    @Test
    fun `enableBiometric throws IllegalStateException when already enabled`() {
        val pin = "123456".toCharArray()
        vault.initialize(pin, codec, biometricEnabled = true)
        SecretBytes.wipe(pin)
        try {
            vault.enableBiometric()
            fail("hasBiometric()==true 时 enableBiometric 应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    @Test
    fun `enableBiometric throws when KeyVault not initialized`() {
        try {
            vault.enableBiometric()
            fail("未初始化时 enableBiometric 应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    // --- initializeWithExistingEntropy(迁移路径) -----------------------

    /**
     * 用现有的熵初始化 KeyVault,关键不变量是:
     *
     * 1. 写出的熵字节 == 调用方传入的熵字节
     * 2. 之后用 PIN 解出来的 handle.entropy 跟那个熵完全一致
     *
     * 这两条合起来就是"迁移路径和 fresh install 路径写出的字段完全一致"的实质保证。
     */
    @Test
    fun `initializeWithExistingEntropy round-trips through PIN`() {
        val expectedEntropy = ByteArray(16) { (it + 7).toByte() }
        val entropy = expectedEntropy.copyOf()
        val pin = "123456".toCharArray()
        vault.initializeWithExistingEntropy(entropy, pin, codec, biometricEnabled = true)

        assertTrue(vault.isInitialized())
        assertTrue(vault.hasPin())
        assertTrue(vault.hasBiometric())

        // 不变量 1:prefs 里写出的熵 == 调用方传入的熵
        val stored = readEntropyFromPrefs()
        assertArrayEquals(expectedEntropy, stored)

        // 不变量 2:用 PIN 解出来 == 同一个熵
        val pin2 = "123456".toCharArray()
        val handle = try {
            vault.unlockWithPin(pin2)
        } finally {
            SecretBytes.wipe(pin2)
        }
        try {
            assertArrayEquals(expectedEntropy, handle.entropy)
            assertEquals(KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN, handle.kind)
        } finally {
            handle.wipe()
            stored.fill(0)
        }
        expectedEntropy.fill(0)
    }

    @Test
    fun `initializeWithExistingEntropy rejects wrong entropy length`() {
        try {
            vault.initializeWithExistingEntropy(
                ByteArray(15), // 短了一字节
                "123456".toCharArray(),
                codec,
                biometricEnabled = true,
            )
            fail("熵不是 16 字节应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 期望
        }
    }

    @Test
    fun `initializeWithExistingEntropy twice throws`() {
        vault.initialize("123456".toCharArray(), codec, biometricEnabled = true)
        SecretBytes.wipe("123456".toCharArray())
        try {
            vault.initializeWithExistingEntropy(
                ByteArray(16) { 0x42 },
                "abcdefgh".toCharArray(),
                codec,
                biometricEnabled = true,
            )
            fail("重复初始化应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 期望
        }
    }

    private fun readEntropyFromPrefs(): ByteArray =
        java.util.Base64.getDecoder().decode(checkNotNull(prefs.getString("kv.master_entropy", null)))
}

/**
 * in-memory [SharedPreferences] 的最小实现,用于 KeyVault 单测。
 *
 * 真实 EncryptedSharedPreferences 依赖 androidx.security + Android Keystore,
 * 单测里完全没法跑;KeyVault 的状态机只关心 String/int 的读写,这层抽象够用了。
 */
private class InMemoryPrefs : EncryptedPrefsFactory {
    private val map: MutableMap<String, Any?> = mutableMapOf()
    override fun open(): SharedPreferences = Impl(map)
    fun getString(key: String, default: String?): String? = map[key] as? String ?: default

    private class Impl(private val map: MutableMap<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl(map)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class EditorImpl(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
            // pending 既记录 put 的最新值,也充当"apply 时哪些 key 要保留"的清单。
            // clear() 会清空 pending 并把所有原 key 加进 removes —— 所以要在清之前
            // 把"原本就在 map 里的 key"抓下来。
            private val initialKeys: Set<String> = map.keys.toSet()
            private val pending: MutableMap<String, Any?> = map.toMutableMap()
            private val removes: MutableSet<String> = mutableSetOf()
            private var cleared = false
            override fun putString(key: String, value: String?) = also { pending[key] = value; cleared = false }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values; cleared = false }
            override fun putInt(key: String, value: Int) = also { pending[key] = value; cleared = false }
            override fun putLong(key: String, value: Long) = also { pending[key] = value; cleared = false }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value; cleared = false }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value; cleared = false }
            override fun remove(key: String) = also { pending.remove(key); removes += key; cleared = false }
            override fun clear() = also {
                pending.clear()
                removes += initialKeys
                cleared = true
            }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (cleared) map.clear()
                removes.forEach { map.remove(it) }
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }
}

/**
 * KeystoreAccess 的替身 —— 不去碰 Android Keystore,直接用一把固定的软件 AES 密钥。
 * "我们正在用 Keystore"这件事本身就只是为了让 [KeyVault] 不去关心密码学的 key 派生;
 * 测试只关心 wrap/unwrap 的语义,所以软件密钥完全足够。
 */
private class FakeKeystoreAccess : KeystoreAccess {

    @Volatile var lastIssuedAlias: String = ""

    /**
     * 测试 seam:模拟"设备没有 secure lock screen / Keystore 不可用"。
     * 真实设备上 `AndroidKeystoreKeyFactory.create` 会抛 [CryptoException.BlobCorrupted]
     * (包装自 `IllegalStateException: Secure lock screen must be enabled`)。
     */
    @Volatile var shouldThrowOnEnsureBiometricKey: Boolean = false

    /**
     * 测试 seam:模拟"非 BlobCorrupted 的任意 Throwable",用于验证
     * v0.4.2 Bug #47 放宽 catch 后的 best-effort 行为。
     * 例如 `KeyStoreException`、`IllegalStateException`(StrongBox 不可用、
     * binder 死锁、OEM 定制 Keystore 异常等)。
     *
     * 优先级高于 [shouldThrowOnEnsureBiometricKey]:即使后者为 true,只要
     * 这里非 null,就用这个 throwable。
     */
    @Volatile var customThrowableToThrow: Throwable? = null

    private val keys: MutableMap<String, SecretKey> = mutableMapOf()
    private val fixedKey: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override val biometricKeyAlias: String = "test_bio_v1"

    override fun ensureBiometricKey(): SecretKey {
        customThrowableToThrow?.let { throw it }
        if (shouldThrowOnEnsureBiometricKey) {
            throw CryptoException.BlobCorrupted(
                "FakeKeystoreAccess 模拟:Keystore 不可用 / 无 secure lock screen",
            )
        }
        keys.getOrPut(biometricKeyAlias) { fixedKey }
        lastIssuedAlias = biometricKeyAlias
        return keys[biometricKeyAlias]!!
    }

    override fun ensureBiometricKeyAlias(): String {
        customThrowableToThrow?.let { throw it }
        if (shouldThrowOnEnsureBiometricKey) {
            throw CryptoException.BlobCorrupted(
                "FakeKeystoreAccess 模拟:Keystore 不可用 / 无 secure lock screen",
            )
        }
        keys.getOrPut(biometricKeyAlias) { fixedKey }
        lastIssuedAlias = biometricKeyAlias
        return biometricKeyAlias
    }

    override fun loadBiometricKey(alias: String): SecretKey =
        keys[alias] ?: throw CryptoException.BlobCorrupted("alias $alias 不存在")

    override fun deleteBiometricKey(alias: String) { keys.remove(alias) }
}
