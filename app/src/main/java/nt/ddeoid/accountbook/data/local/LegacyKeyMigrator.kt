package nt.ddeoid.accountbook.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.security.crypto.EntropySource
import nt.ddeoid.accountbook.security.crypto.MasterKeyFactory
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import nt.ddeoid.accountbook.security.lock.KeyVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.3.0 → v0.4.0 数据迁移的**编排器**。
 *
 * 一次性、不可重入。设计目标是把"开旧库 → rekey → 关库 → 写 KeyVault → 抹旧口令"
 * 这条线收敛在一处,任何一步失败都能定位到具体步骤。
 *
 * ## 步骤(顺序敏感)
 *
 * ```
 *  1. MasterKeyFactory.generate(codec)             纯 CPU,生成新熵
 *  2. fromEntropy → SecretBytes newMasterKey      32 字节 SQLCipher 主密钥
 *  3. rekeyer.rekey(oldPassphrase, newMasterKey)   落盘,事务级原子
 *  4. keyVault.initializeWithExistingEntropy(...)  写新 vault
 *  5. passphraseProvider.wipe()                   最佳努力
 * ```
 *
 * ## 失败语义
 *
 * - 步骤 1 / 2 / 4 失败 → DB 仍用旧口令,KeyVault 未初始化 → 下次启动走"重试迁移"
 * - 步骤 3 失败 → DB 仍用旧口令(SQLCipher 事务级原子) → 下次启动走"重试迁移"
 * - 步骤 4 成功 / 步骤 5 失败 → DB 用新口令,KeyVault 已初始化,旧口令仍残留 ——
 *   旧口令本身已经打不开任何东西(DB 已重 key),属于 best-effort cleanup,不阻塞用户
 *
 * ## MigrationMarker
 *
 * 入口处写 `inProgress = true`,`finally` 里写 `inProgress = false`。如果进程在
 * 步骤 3 之后、4 之前被杀,下次启动看到 marker 为 true → UI 引导用户"从备份恢复",
 * 而不是傻乎乎地再调一次 [migrate] 然后卡死在"旧口令打不开文件"。
 */
@Singleton
class LegacyKeyMigrator @Inject constructor(
    private val passphraseProvider: DatabasePassphraseProvider,
    private val rekeyer: DatabaseRekeyer,
    private val keyVault: KeyVault,
    private val migrationMarker: MigrationMarker,
    private val entropySource: EntropySource,
) {

    /**
     * 跑一次迁移。
     *
     * @param newPin 用户在 wizard 里输入的新 PIN,本方法用完**不**擦 —— 调用方(wizard)
     *   在用户走完整个流程后负责擦,迁移期间它还要在 UI 里保留。
     * @param mnemonicCodec 用于编码"万一用户没抄过 12 词,旧库用户也想看一眼" —— 旧库用户
     *   理论上从来没生成过助记词,但 [KeyVault.initializeWithExistingEntropy] 不消费
     *   codec,所以这里只是把它传给接口合同。
     * @return 一个持有**新熵**的 [KeyVault.MasterKeyHandle] —— DB 已经用这个熵派生
     *   的主密钥加密了,调用方(迁移 wizard)拿这个 handle 紧接着调
     *   [nt.ddeoid.accountbook.security.lock.LockController.finishMigration] 把 DB 打开、
     *   状态推到 Unlocked。**handle 的所有权移交 controller,这里不再 wipe**。
     * @throws IllegalStateException 前置条件不满足(没 legacy 口令、KeyVault 已初始化、DB 已打开)
     * @throws DatabaseOpenException 旧口令打不开文件(损坏或不是这把)
     * @throws DatabaseRekeyException rekey SQL 失败(SQLCipher 事务回滚,DB 不变)
     */
    suspend fun migrate(
        newPin: CharArray,
        mnemonicCodec: MnemonicCodec,
    ): KeyVault.MasterKeyHandle = withContext(Dispatchers.IO) {
        check(passphraseProvider.hasLegacy()) {
            "LegacyKeyMigrator 只能从 legacy 状态调起 —— passphraseProvider.hasLegacy()=false"
        }
        check(!keyVault.isInitialized()) {
            "LegacyKeyMigrator 不能在 KeyVault 已初始化后调 —— 请走 LockController"
        }
        check(!migrationMarker.inProgress) {
            "检测到上次迁移中断 —— 请引导用户从备份恢复,不要重试 migrate"
        }

        // 进程中断保护:rekey 之前写标记,完成之后(不论成败)清掉
        migrationMarker.inProgress = true
        try {
            // 步骤 1+2:生成新熵 + 派生 SQLCipher 主密钥
            val generated = MasterKeyFactory.generate(mnemonicCodec, entropySource)
            try {
                // 步骤 3:用旧口令开文件 → rekey → 关闭
                val legacyBytes = passphraseProvider.getOrCreate()
                SecretBytes(legacyBytes).use { oldPass ->
                    generated.masterKey.use { newMaster ->
                        rekeyer.rekey(oldPass, newMaster)
                    }
                }

                // 步骤 4:写 KeyVault。这步之后,KeyVault.isInitialized()=true,
                // LockController.finishMigration 可以接着开库。熵在用之前先 copyOf 出来
                // 留给返回的 handle —— `initializeWithExistingEntropy` 会自己 wipe 自己的
                // 入参副本,但 generated.entropy 还要在 finally 里被 wipe。
                val entropyForHandle = generated.entropy.copyOf()
                keyVault.initializeWithExistingEntropy(
                    entropy = generated.entropy.copyOf(),
                    pin = newPin,
                    mnemonicCodec = mnemonicCodec,
                )

                // 步骤 5:清旧口令 —— best-effort。这步失败不影响安全(DB 已经重 key 了),
                // 但旧口令残留在 prefs 里会让"hasLegacy()"误报 true,所以尽量清掉。
                runCatching { passphraseProvider.wipe() }
                    .onFailure { android.util.Log.w(TAG, "wipe legacy 失败(非致命)", it) }

                // 注意:return 在 try 块里;finally 还会跑一次 wipe generated.entropy
                // —— entropyForHandle 是独立的副本,不受影响。
                KeyVault.MasterKeyHandle(
                    entropy = entropyForHandle,
                    kind = KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN,
                )
            } finally {
                generated.entropy.fill(0)
            }
        } finally {
            migrationMarker.inProgress = false
        }
    }

    private companion object {
        const val TAG = "LegacyKeyMigrator"
    }
}
