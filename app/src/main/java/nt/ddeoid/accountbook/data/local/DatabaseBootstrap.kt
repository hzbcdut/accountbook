package nt.ddeoid.accountbook.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.data.seed.SeedDataInitializer
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动时用"设备上那把旧口令"打开数据库。
 *
 * 这是 v0.3.0 的原始行为:密钥是首启生成的随机 32 字节,存在 EncryptedSharedPreferences
 * 里,没有任何用户秘密参与。Q2=C 允许用户**不启用**应用锁,那种情况下就得走这条路 ——
 * 所以它不是过渡代码,而是"未启用锁"这个状态的正式实现。
 *
 * 启用锁之后,开库的钥匙改由 LockController 从 PIN / 生物识别 / 助记词三条路径之一
 * 解出来,这个类不再参与。
 *
 * ## 为什么是 suspend
 *
 * [DatabaseProvider.open] 会同步打开 SQLCipher 并验证密钥,这活不能放主线程
 * (冷启动会明显变慢,而且是 StrictMode 的磁盘读违规)。所以本方法挂起,调用方在
 * IO 线程上启动它;UI 侧靠 [DatabaseProvider.deferred] 等库打开,不会因为抢跑而崩。
 *
 * ## 种子数据的时机
 *
 * [SeedDataInitializer] 原来挂在 `Application.onCreate` 上,而它注入了 [AppDatabase]
 * —— 那会在**解锁之前**就把库打开,直接击穿 Q4=C。现在改成开库成功后才播种。
 */
@Singleton
class DatabaseBootstrap @Inject constructor(
    private val databaseProvider: DatabaseProvider,
    private val passphraseProvider: DatabasePassphraseProvider,
    private val seedDataInitializer: SeedDataInitializer,
) {

    /**
     * 用设备上的旧口令开库并播种。幂等:库已经开着就只确保种子跑过。
     *
     * 口令在返回前就被擦掉 —— [DatabaseProvider.open] 自己留了一份副本。
     */
    suspend fun openWithLegacyKey() = withContext(Dispatchers.IO) {
        if (!databaseProvider.isOpen) {
            val passphrase = SecretBytes(passphraseProvider.getOrCreate())
            try {
                databaseProvider.open(passphrase)
            } finally {
                passphrase.wipe()
            }
        }
        seedDataInitializer.initialize()
    }
}
