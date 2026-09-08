package nt.ddeoid.accountbook.security.lock.ui

import android.security.keystore.KeyPermanentlyInvalidatedException
import nt.ddeoid.accountbook.data.local.DatabaseOpenException
import nt.ddeoid.accountbook.security.crypto.CryptoException
import nt.ddeoid.accountbook.security.crypto.MnemonicException
import nt.ddeoid.accountbook.security.lock.LockException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LockError] 转译层测试。
 *
 * 测的是**映射规则**,不是文案本身。文案在 strings.xml,跨语言、跨设备
 * 翻译时整片会变 —— 那是 linter / 翻译流程的事。这里钉死的是:
 * "错的 PIN → WrongPin" 这种业务级契约,不能因为改 i18n 就悄悄改错。
 */
class LockErrorTest {

    // --- CryptoException:全部归到 WrongPin(不暴露内部状态) -------------

    @Test
    fun `UnwrapFailed maps to WrongPin`() {
        val cause = RuntimeException("AES/GCM tag mismatch")
        val error = LockError.from(CryptoException.UnwrapFailed(cause))
        assertEquals(LockError.WrongPin, error)
        assertEquals(LockError.WrongPin.messageRes(), error.messageRes())
    }

    @Test
    fun `BlobCorrupted maps to WrongPin`() {
        // 故意:BlobCorrupted 也翻译成 WrongPin —— GCM 解不开时其实没法区分"密钥不对"
        // 还是"blob 损坏",暴露给用户会泄露内部状态。
        val error = LockError.from(CryptoException.BlobCorrupted("blob too short"))
        assertEquals(LockError.WrongPin, error)
    }

    @Test
    fun `WordListCorrupted maps to WrongPin`() {
        // 词表坏了应该**启动时就炸**,不会跑到这里。如果真的跑到这里(比如某个意外路径),
        // 至少 UI 不会崩溃 —— 把它当作普通的"解锁失败"显示,而不是致命错误。
        val error = LockError.from(CryptoException.WordListCorrupted("sha256 mismatch"))
        assertEquals(LockError.WrongPin, error)
    }

    @Test
    fun `CryptoException subclasses do not leak internal type info`() {
        // 关键不变量:UI 看到的文案/ID 一致,即便底层异常类型不一样。
        // 这一条保住"不在 UI 上区分 UnwrapFailed / BlobCorrupted"的设计承诺。
        val unwrap = LockError.from(CryptoException.UnwrapFailed(RuntimeException()))
        val blob = LockError.from(CryptoException.BlobCorrupted("x"))
        assertEquals(unwrap.messageRes(), blob.messageRes())
        assertEquals(unwrap, blob)
    }

    // --- MnemonicException:每种类型都要走到不同分支 --------------------

    @Test
    fun `UnknownWord preserves word and position`() {
        val error = LockError.from(MnemonicException.UnknownWord("abandn", 4))
        // 拿到的是 data class(不是 WrongPin 这种 object),字面值要保住 —— UI 会显示
        // "第 5 个词 abandn 不在词表里" 那种精确提示。
        val unknown = error as LockError.MnemonicUnknownWord
        assertEquals("abandn", unknown.word)
        assertEquals(4, unknown.position)
    }

    @Test
    fun `WrongWordCount maps to MnemonicWrongCount`() {
        val error = LockError.from(MnemonicException.WrongWordCount(13))
        assertEquals(LockError.MnemonicWrongCount, error)
    }

    @Test
    fun `ChecksumMismatch maps to MnemonicChecksumMismatch`() {
        val error = LockError.from(MnemonicException.ChecksumMismatch)
        assertEquals(LockError.MnemonicChecksumMismatch, error)
    }

    @Test
    fun `WrongEntropySize falls through to ChecksumMismatch bucket`() {
        // WrongEntropySize 在 MnemonicCodec 里只在 encode 阶段抛。用户能跑到的只有
        // decode → UnknownWord / WrongWordCount / ChecksumMismatch;encode 是 KeyVault
        // 内部用的,出错也不会到 UI。所以这里不当 first-class 错误类型处理,
        // 归到 ChecksumMismatch(语义最接近的兜底)。
        val error = LockError.from(MnemonicException.WrongEntropySize(7))
        assertEquals(LockError.MnemonicChecksumMismatch, error)
    }

    // --- LockException:冷却期是唯一非密码学失败 -----------------------

    @Test
    fun `InCooldown carries retry-after ms`() {
        val error = LockError.from(LockException.InCooldown(30_000L))
        val cooldown = error as LockError.Cooldown
        assertEquals(30_000L, cooldown.retryAfterMs)
    }

    // --- 生物识别失效 -----------------------------------------------

    @Test
    fun `KeyPermanentlyInvalidated maps to BiometricInvalidated`() {
        // 这是 Android Keystore 在用户改了设备锁屏密码 / 清除所有凭据后抛的。
        // BiometricGate 接住后会原样转给我们。
        val error = LockError.from(KeyPermanentlyInvalidatedException("alias: bio_v1"))
        assertEquals(LockError.BiometricInvalidated, error)
    }

    // --- 兜底:未知错误 -----------------------------------------------

    @Test
    fun `unmatched throwable maps to Unknown with message`() {
        val error = LockError.from(IllegalStateException("something went wrong"))
        val unknown = error as LockError.Unknown
        assertEquals("something went wrong", unknown.detail)
    }

    @Test
    fun `unmatched throwable with null message falls back to class simpleName`() {
        // 不要让 NullPointerException 滑进 UI。
        val error = LockError.from(object : RuntimeException() {})
        val unknown = error as LockError.Unknown
        assertNotNull(unknown.detail)
        assertTrue(
            "detail 应该是类名或某个非空字符串;实际 \"${unknown.detail}\"",
            unknown.detail.isNotEmpty(),
        )
    }

    @Test
    fun `Unknown is distinct from WrongPin`() {
        // 兜底路径不能被误判为 WrongPin —— UI 上 WrongPin 触发"再试一次",
        // Unknown 触发"出错了,详情点开看"的展开面板,行为不同。
        val unknown = LockError.from(RuntimeException("boom"))
        val wrongPin = LockError.WrongPin
        assertNotEquals(unknown, wrongPin)
        assertNotEquals(unknown.messageRes(), wrongPin.messageRes())
    }

    // --- DatabaseOpenException 暂时不归到 DatabaseCorrupted ------------

    @Test
    fun `DatabaseOpenException falls through to Unknown (not DatabaseCorrupted)`() {
        // 计划里有把 DatabaseOpenException → DatabaseCorrupted 的设计,但当前
        // 决策是:DatabaseOpenException 只会从 LockController.finishSetup 内部抛,
        // 已经在 controller 层被 openDatabaseWithOrWipe 拦截了;UI 这层
        // 不应该再独立看到这条 —— 所以归到 Unknown,UI 不会把它当成"数据损坏"。
        // 这一条把"不要把 DatabaseCorrupted 暴露给 UI"的设计钉死。
        val error = LockError.from(DatabaseOpenException(RuntimeException("sqlcipher: file is not a database")))
        assertTrue(
            "DatabaseOpenException 不应是 DatabaseCorrupted(那是 fatal 路径);实际 $error",
            error !is LockError.DatabaseCorrupted,
        )
        // 它应该是 Unknown —— message 里有原始 cause 的线索
        assertTrue(error is LockError.Unknown)
    }

    // --- messageRes:每个 variant 都有非 0 的 R.id ----------------------

    @Test
    fun `messageRes returns a non-zero R id for every variant`() {
        val variants: List<LockError> = listOf(
            LockError.WrongPin,
            LockError.DatabaseCorrupted,
            LockError.BiometricInvalidated,
            LockError.MnemonicWrongCount,
            LockError.MnemonicChecksumMismatch,
            LockError.Cooldown(12345L),
            LockError.MnemonicUnknownWord("foo", 7),
            LockError.Unknown("detail"),
        )
        val ids = variants.map { it.messageRes() }
        ids.forEach { id ->
            assertTrue("messageRes 返回了 0,意味着 @StringRes ID 没找到:$id", id != 0)
            assertNotNull("messageRes 不应返回 null:$id", id)
        }
        // 8 个 variant 必须各自拿到不同 ID —— 不能都映射到同一个 string 上去。
        assertEquals(
            "不同 variant 共享 messageRes 是设计 bug",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `messageRes is stable for data classes`() {
        // data class 的 messageRes 应该跟其他同型实例一致(纯函数)。
        val a = LockError.Cooldown(1000L)
        val b = LockError.Cooldown(9999L)
        assertEquals(a.messageRes(), b.messageRes())
    }
}
