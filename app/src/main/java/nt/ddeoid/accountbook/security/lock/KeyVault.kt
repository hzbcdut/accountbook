package nt.ddeoid.accountbook.security.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import nt.ddeoid.accountbook.security.crypto.CryptoException
import nt.ddeoid.accountbook.security.crypto.EntropySource
import nt.ddeoid.accountbook.security.crypto.KeyWrapper
import nt.ddeoid.accountbook.security.crypto.MasterKeyFactory
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.PinKdf
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import java.security.KeyStore
import java.util.Base64
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * master key 的**存储层**。
 *
 * 这是 Q1=A 决议在代码里的形态:master key 本身是一段随机熵,持久化时**不直接落盘**,
 * 而是用每条解锁路径各裹一层。打开任意一层就能拿到 entropy,再走 [MasterKeyFactory]
 * 还原出 master key。锁死任何一条路径,其他路径还能开 —— 这是用户预期,也是设计目标。
 *
 * ## 存储布局
 *
 * 全在一个 [EncryptedSharedPreferences] 文件里:
 *
 * | key                  | 内容                                                       |
 * |----------------------|------------------------------------------------------------|
 * | `kv.master_entropy`  | 16 字节 BIP39 熵(Q10=A:熵本身就是主密钥的种子)             |
 * | `kv.pin_salt`        | PIN KDF 的盐                                               |
 * | `kv.pin_blob`        | master entropy 用 PIN 派生密钥 AES-GCM 包裹后的 blob       |
 * | `kv.bio_blob`        | master entropy 用 Keystore 密钥 AES-GCM 包裹后的 blob     |
 * | `kv.bio_key_alias`   | Keystore 里那条 AES 密钥的别名                             |
 * | `kv.kdf_iterations`  | PBKDF2 迭代次数;仅作为前向兼容哨兵                        |
 *
 * 主密钥在这里**只以 entropy 形式**存,没有"明文 master key"字段;Q10=A 要求熵等于
 * 主密钥,所以这条路就是主密钥 —— 但用户拿不到这个文件里的内容,看到的全是 AES-GCM
 * 密文 + EncryptedSharedPreferences 自己的二次加密。
 *
 * ## 多路径的独立性
 *
 * [KeyWrapper.WrapContext] 的 AAD 把每条路径的 blob **钉死在它自己的路径上**:
 * PIN 路径解生物识别 blob 一定会失败(Q1=A 的语义之一)。
 *
 * ## 测试性
 *
 * [EncryptedPrefsFactory] / [KeystoreAccess] 是注入的工厂接口,生产走真实 Android
 * Keystore,测试可以传 fake —— 这样 PIN 路径和"凭熵派生 master key"这两条纯逻辑
 * 都能在 JVM 单测里跑。
 */
@Singleton
class KeyVault @Inject constructor(
    private val prefsFactory: EncryptedPrefsFactory,
    private val keystoreAccess: KeystoreAccess,
    private val entropySource: EntropySource,
    private val wrapper: KeyWrapper,
    private val pinKdfIterations: Int,
) {

    /**
     * 已经初始化过(熵已写入)。
     *
     * 这是 SetupWizard 的入口判定 —— 真正的"应用锁是否启用"判定在 [LockPrefs]
     * 里。Q2=C 允许用户在 wizard 里跳过,所以"已初始化"≠"启用应用锁"。
     */
    fun isInitialized(): Boolean = withPrefs { it.contains(KEY_MASTER_ENTROPY) }

    fun hasPin(): Boolean = withPrefs { it.contains(KEY_PIN_BLOB) && it.contains(KEY_PIN_SALT) }
    fun hasBiometric(): Boolean = withPrefs { it.contains(KEY_BIO_BLOB) }

    /**
     * 首启初始化:生成熵 → 派生 master key → 用 PIN 派生密钥和 Keystore 密钥各裹一次。
     *
     * @param pin 用户选择的 PIN/passphrase。**调用方在调用前需要持有它,调用后立即擦掉**。
     * @param biometricEnabled 用户在 wizard 步骤 3 是否勾选「启用生物识别」。**true 也只
     *   是 best-effort** —— 设备没有 secure lock screen 时 Keystore 会拒绝创建
     *   `setUserAuthenticationRequired` 的密钥,这种情况下 [initializeWithExistingEntropy]
     *   会捕获异常、跳过生物识别 blob 的写入,setup 仍正常完成;`hasBiometric()` 返回 false,
     *   后续可在 Settings 里通过 [enableBiometric] 重试。
     * @return [SetupResult] 包含 master key handle + 12 词,**调用方必须在抄写完
     *   助记词并展示结束后擦除 handle**。
     * @throws IllegalStateException 已经初始化过
     */
    fun initialize(
        pin: CharArray,
        mnemonicCodec: MnemonicCodec,
        biometricEnabled: Boolean,
    ): SetupResult {
        val generated = MasterKeyFactory.generate(mnemonicCodec, entropySource)
        try {
            // ⚠️ 必须在调底层方法**之前**就把 entropy 复制一份出来:
            // initializeWithExistingEntropy 在 finally 里会 wipe 它自己收到的入参,
            // 但 generated.entropy 是同一个 ByteArray 引用,wipe 完我们手里那份也变 0。
            val entropyForResult = generated.entropy.copyOf()
            // 走迁移路径共用的 wrap/persist 代码 —— 两条路径写出的字段必须完全一致,
            // 否则"升级用户的 KeyVault 和 fresh install 的 KeyVault 长得不一样"这种
            // 静默 bug 早晚会冒头。
            val setupResult = initializeWithExistingEntropy(
                entropy = generated.entropy,
                pin = pin,
                mnemonicCodec = mnemonicCodec,
                biometricEnabled = biometricEnabled,
            )
            // setupResult.masterKey 是底层方法为了兼容调用方生成的临时副本;外部拿到的是
            // generated.masterKey(由 generate 派生 + 由 generated.mnemonic 配套)。两者字节
            // 完全相同(Q10=A + HKDF 确定性),但生命周期管理要分开。
            try { setupResult.masterKey.wipe() } catch (_: Throwable) { /* already wiped */ }
            return SetupResult(
                masterKey = generated.masterKey,
                mnemonic = generated.mnemonic,
                entropy = entropyForResult,
            )
        } catch (t: Throwable) {
            generated.masterKey.wipe()
            generated.entropy.fill(0)
            throw t
        }
    }

    /**
     * 用**已存在**的熵初始化 KeyVault。
     *
     * 这是 [initialize] 和 [LegacyKeyMigrator] 共用的底层方法 —— 前者生成熵后调这里,
     * 后者把已经算好的熵(为了 rekey SQLCipher 提前算的)喂进来,绕过生成步骤。
     *
     * **两条路径共享同一段 wrap/persist 代码**,保证 prefs 里写出的字段一字不差。
     *
     * @param entropy 16 字节 BIP39 熵。本方法在返回前会清零它。
     * @param pin 用户选择的 PIN/passphrase,本方法用完会 wipe 派生的密钥。
     * @param biometricEnabled 是否尝试启用生物识别路径。**best-effort**:设备没有 secure
     *   lock screen / Keystore 不可用时会被 [CryptoException.BlobCorrupted] 拦截,只
     *   跳过生物识别 blob 的写入,PIN 路径不受影响。
     * @return [SetupResult],其中 [SetupResult.masterKey] 是一次性副本(底层方法生成,
     *   [initialize] 不会暴露给调用方)。`[SetupResult.mnemonic]` 在迁移路径下为空,
     *   因为旧库用户从来没见过 12 词。
     * @throws IllegalStateException 已经初始化过
     */
    fun initializeWithExistingEntropy(
        entropy: ByteArray,
        pin: CharArray,
        @Suppress("UNUSED_PARAMETER") mnemonicCodec: MnemonicCodec,
        biometricEnabled: Boolean,
    ): SetupResult {
        check(!isInitialized()) { "KeyVault 已经初始化,不要重复调用 initializeWithExistingEntropy" }
        require(entropy.size == 16) {
            "熵必须是 16 字节 (BIP39 128-bit 档位),实际 ${entropy.size}"
        }
        val salt = entropySource.nextSalt()
        val pinKey = PinKdf.derive(pin, salt, pinKdfIterations)
        // 生物识别路径是可选的。设备没有 secure lock screen 时 Keystore 会拒绝创建
        // setUserAuthenticationRequired 的密钥;只 catch BlobCorrupted,不 catch 父类
        // CryptoException —— 其他错误(如 unwrap 失败)继续响亮失败,免得吞掉真 bug。
        val bioKey: javax.crypto.SecretKey? = if (biometricEnabled) {
            try {
                keystoreAccess.ensureBiometricKey()
            } catch (e: CryptoException.BlobCorrupted) {
                android.util.Log.w(
                    "KeyVault",
                    "生物识别密钥创建失败,跳过生物识别路径;setup 仍正常完成," +
                        "后续可在 Settings → enableBiometric 重试",
                    e,
                )
                null
            }
        } else null
        try {
            // 熵是持久化层的"主语":wrap 它、存它(Q10=A)。masterKey 不在 blob 里出现。
            val pinBlob = SecretBytes(entropy.copyOf()).use { copyForPin ->
                wrapper.wrap(pinKey.bytes, copyForPin, KeyWrapper.WrapContext.PIN)
            }
            val editor = withPrefs { prefs ->
                prefs.edit()
                    .putString(KEY_MASTER_ENTROPY, base64(entropy))
                    .putString(KEY_PIN_SALT, base64(salt))
                    .putInt(KEY_KDF_ITERATIONS, pinKdfIterations)
                    .putString(KEY_PIN_BLOB, pinBlob)
            }
            if (bioKey != null) {
                val bioBlob = SecretBytes(entropy.copyOf()).use { copyForBio ->
                    wrapper.wrap(bioKey, copyForBio, KeyWrapper.WrapContext.BIOMETRIC)
                }
                editor
                    .putString(KEY_BIO_BLOB, bioBlob)
                    .putString(KEY_BIO_KEY_ALIAS, keystoreAccess.biometricKeyAlias)
                    .apply()
            } else {
                editor.apply()
            }
            // ⚠️ entropy.fill(0) 必须在 masterKey 已经从 copyOf 拿到手之后,否则
            // fromEntropy(zero) 会派生出一个固定的全零 master key,DB 用它加密,
            // 但 unlock 时从 PIN blob 取回的是真熵,派生出另一个 key → "file is not a database"。
            val realEntropyCopy = entropy.copyOf()
            return SetupResult(
                masterKey = MasterKeyFactory.fromEntropy(entropy.copyOf()),
                mnemonic = emptyList(),
                entropy = realEntropyCopy,
            )
        } finally {
            pinKey.wipe()
            entropy.fill(0)
        }
    }

    /** PIN 解锁。返回的 handle 用完必须 [MasterKeyHandle.wipe]。 */
    fun unlockWithPin(pin: CharArray): MasterKeyHandle {
        val saltB64 = withPrefs { it.getString(KEY_PIN_SALT, null) }
            ?: throw CryptoException.BlobCorrupted("PIN salt 缺失,无法走 PIN 路径")
        val blob = withPrefs { it.getString(KEY_PIN_BLOB, null) }
            ?: throw CryptoException.BlobCorrupted("PIN blob 缺失,无法走 PIN 路径")
        val iterations = withPrefs { it.getInt(KEY_KDF_ITERATIONS, pinKdfIterations) }
        val salt = base64Decode(saltB64)
        val pinKey = PinKdf.derive(pin, salt, iterations)
        try {
            val entropy = wrapper.unwrap(pinKey.bytes, blob, KeyWrapper.WrapContext.PIN).bytes
            return MasterKeyHandle(entropy, MasterKeyHandle.Kind.DERIVED_FROM_PIN)
        } finally {
            pinKey.wipe()
        }
    }

    /**
     * 生物识别解锁。需要 Keystore SecretKey 是 `setUserAuthenticationRequired(true)` 的,
     * Android Keystore 在用户认证通过前不会允许用它解密。
     */
    fun unlockWithBiometric(): MasterKeyHandle {
        val alias = withPrefs { it.getString(KEY_BIO_KEY_ALIAS, null) }
            ?: throw CryptoException.BlobCorrupted("生物识别密钥别名缺失")
        val blob = withPrefs { it.getString(KEY_BIO_BLOB, null) }
            ?: throw CryptoException.BlobCorrupted("生物识别 blob 缺失")
        val key = keystoreAccess.loadBiometricKey(alias)
        val entropy = wrapper.unwrap(key, blob, KeyWrapper.WrapContext.BIOMETRIC).bytes
        return MasterKeyHandle(entropy, MasterKeyHandle.Kind.DERIVED_FROM_BIOMETRIC)
    }

    /**
     * 助记词恢复。助记词本身就是熵的人类可读编码(Q10=A),所以这条路径**完全绕开
     * PIN / Keystore**,直接 [MnemonicCodec.decode] → [MasterKeyFactory.fromEntropy]。
     *
     * 成功后会把熵写回 prefs,作为"re-enrollment":这样恢复过的设备下一次开机就能走
     * PIN / 生物识别,不必每次都输 12 个词。**调用方需要 PIN 才能重新启用生物识别路径**。
     */
    fun recoverFromMnemonic(words: List<String>, mnemonicCodec: MnemonicCodec): MasterKeyHandle {
        val entropy = mnemonicCodec.decode(words)
        try {
            persistEntropy(entropy)
            return MasterKeyHandle(entropy.copyOf(), MasterKeyHandle.Kind.DERIVED_FROM_MNEMONIC)
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * 改 PIN。保留原熵;用新 PIN 重新派生密钥并裹一次。
     *
     * 注意:**不清**生物识别路径 —— 那条路径用的不是 PIN 派生密钥。如果用户只换了 PIN,
     * 生物识别依然能用。
     */
    fun changePin(oldPin: CharArray, newPin: CharArray) {
        check(isInitialized()) { "尚未初始化" }
        // 用旧 PIN 解开,确认旧 PIN 真的对,顺便拿到熵
        val oldHandle = unlockWithPin(oldPin)
        try {
            val entropyCopy = oldHandle.entropy.copyOf()
            try {
                val salt = entropySource.nextSalt()
                val newKey = PinKdf.derive(newPin, salt, pinKdfIterations)
                try {
                    val newBlob = SecretBytes(entropyCopy).use { entropyHandle ->
                        wrapper.wrap(newKey.bytes, entropyHandle, KeyWrapper.WrapContext.PIN)
                    }
                    withPrefs { prefs ->
                        prefs.edit()
                            .putString(KEY_PIN_SALT, base64(salt))
                            .putInt(KEY_KDF_ITERATIONS, pinKdfIterations)
                            .putString(KEY_PIN_BLOB, newBlob)
                            .apply()
                    }
                } finally {
                    newKey.wipe()
                }
            } finally {
                entropyCopy.fill(0)
            }
        } finally {
            oldHandle.wipe()
        }
    }

    /** 重置生物识别路径:删旧 Keystore 密钥 + 旧 blob,重新裹。 */
    fun reenrollBiometric() {
        check(isInitialized()) { "尚未初始化" }
        val alias = withPrefs { it.getString(KEY_BIO_KEY_ALIAS, null) }
        if (alias != null) keystoreAccess.deleteBiometricKey(alias)
        val freshAlias = keystoreAccess.ensureBiometricKeyAlias()
        val entropyB64 = withPrefs { it.getString(KEY_MASTER_ENTROPY, null) }
            ?: throw CryptoException.BlobCorrupted("熵缺失,无法重置生物识别")
        val entropy = base64Decode(entropyB64)
        try {
            val bioKey = keystoreAccess.loadBiometricKey(freshAlias)
            val bioBlob = SecretBytes(entropy).use { wrapper.wrap(bioKey, it, KeyWrapper.WrapContext.BIOMETRIC) }
            withPrefs { prefs ->
                prefs.edit()
                    .putString(KEY_BIO_BLOB, bioBlob)
                    .putString(KEY_BIO_KEY_ALIAS, freshAlias)
                    .apply()
            }
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * Settings 驱动的一次性生物识别 enroll。前提:KeyVault 已经初始化过(熵在 prefs 里),
     * 但当前**没有**生物识别路径(`hasBiometric() == false`)。
     *
     * 与 [reenrollBiometric] 的区别:这是"首次启用",不需要删除旧 Keystore 密钥;
     * [reenrollBiometric] 是"重新 enroll 后旋转别名" —— 两条路径语义不同,合并会
     * 让 [reenrollBiometric] 的别名轮换副作用漏到首次启用上。
     *
     * @return Result.success 表示生物识别路径已写入 prefs;Result.failure 包装
     *   [CryptoException.BlobCorrupted] 或 [IllegalStateException]。**不抛出**。
     *   调用方(未来的 Settings UI)应当捕获并在 UI 上提示(例如"设备未设置锁屏,
     *   无法启用生物识别")。
     */
    fun enableBiometric(): Result<Unit> {
        // check(...) 抛 IllegalStateException 是契约性的 —— 调用方(未初始化的 KeyVault
        // / 已经启用过生物识别的状态)写错了,所以必须**作为异常抛出**,不要包进
        // Result.failure(否则未来 Settings UI 还要再区分 failure 到底是"写错了"
        // 还是"环境不支持",多一层)。
        check(isInitialized()) { "KeyVault 尚未初始化" }
        check(!hasBiometric()) {
            "生物识别路径已存在 —— 如需重置请先调 removeBiometricPath() 或 reenrollBiometric()"
        }
        return runCatching {
            val bioKey = keystoreAccess.ensureBiometricKey()
            val entropyB64 = withPrefs { it.getString(KEY_MASTER_ENTROPY, null) }
                ?: throw CryptoException.BlobCorrupted("熵缺失,无法启用生物识别")
            val entropy = base64Decode(entropyB64)
            try {
                val bioBlob = SecretBytes(entropy).use {
                    wrapper.wrap(bioKey, it, KeyWrapper.WrapContext.BIOMETRIC)
                }
                withPrefs { prefs ->
                    prefs.edit()
                        .putString(KEY_BIO_BLOB, bioBlob)
                        .putString(KEY_BIO_KEY_ALIAS, keystoreAccess.biometricKeyAlias)
                        .apply()
                }
            } finally {
                entropy.fill(0)
            }
        }
    }

    /** 完全清空 —— 这是"禁用应用锁"的写操作。 */
    fun wipe() {
        val alias = withPrefs { it.getString(KEY_BIO_KEY_ALIAS, null) }
        if (alias != null) keystoreAccess.deleteBiometricKey(alias)
        withPrefs { prefs ->
            prefs.edit().clear().apply()
        }
    }

    /** 仅擦 PIN 路径(用户主动取消 PIN)。保留熵 + 生物识别。 */
    fun removePinPath() {
        withPrefs { prefs ->
            prefs.edit()
                .remove(KEY_PIN_SALT)
                .remove(KEY_PIN_BLOB)
                .remove(KEY_KDF_ITERATIONS)
                .apply()
        }
    }

    /** 仅擦生物识别路径(用户主动关闭生物识别)。 */
    fun removeBiometricPath() {
        val alias = withPrefs { it.getString(KEY_BIO_KEY_ALIAS, null) }
        if (alias != null) keystoreAccess.deleteBiometricKey(alias)
        withPrefs { prefs ->
            prefs.edit()
                .remove(KEY_BIO_BLOB)
                .remove(KEY_BIO_KEY_ALIAS)
                .apply()
        }
    }

    private fun persistEntropy(entropy: ByteArray) {
        withPrefs { prefs ->
            prefs.edit()
                .putString(KEY_MASTER_ENTROPY, base64(entropy))
                .apply()
        }
    }

    private inline fun <T> withPrefs(block: (SharedPreferences) -> T): T = block(prefsFactory.open())

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun base64Decode(s: String): ByteArray = Base64.getDecoder().decode(s)

    /** [initialize] 的产物。[masterKey] 必须在用户抄完助记词后调 [SecretBytes.wipe]。 */
    class SetupResult(
        val masterKey: SecretBytes,
        val mnemonic: List<String>,
        /**
           * 16 字节 BIP39 熵的副本,用于在 wizard 结束后构造 [MasterKeyHandle]。
           * 调用方持有后必须 [ByteArray.fill](0) 擦除 —— [initialize] 已经把自己的
           * [MasterKeyFactory.Generated.entropy] 副本擦了,这份是独立副本。
           */
        val entropy: ByteArray,
    )

    /**
     * 已解锁状态的 master key handle。
     *
     * [entropy] 是 16 字节 BIP39 熵(Q10=A:这就是主密钥的种子)。持有者**必须**
     * 用完调 [wipe],否则熵会一直留在内存里。
     *
     * 持有者负责在调用 [nt.ddeoid.accountbook.security.crypto.MasterKeyFactory] 派生
     * 出 SQLCipher 口令并把它交给 [nt.ddeoid.accountbook.data.local.DatabaseProvider.open]
     * 之后立即擦除。
     */
    class MasterKeyHandle(
        @JvmField val entropy: ByteArray,
        val kind: Kind,
    ) {
        enum class Kind { DERIVED_FROM_PIN, DERIVED_FROM_BIOMETRIC, DERIVED_FROM_MNEMONIC }

        fun wipe() {
            entropy.fill(0)
        }

        inline fun <T> use(block: (ByteArray) -> T): T {
            try {
                return block(entropy)
            } finally {
                wipe()
            }
        }
    }

    private companion object {
        const val KEY_MASTER_ENTROPY = "kv.master_entropy"
        const val KEY_PIN_SALT = "kv.pin_salt"
        const val KEY_PIN_BLOB = "kv.pin_blob"
        const val KEY_BIO_BLOB = "kv.bio_blob"
        const val KEY_BIO_KEY_ALIAS = "kv.bio_key_alias"
        const val KEY_KDF_ITERATIONS = "kv.kdf_iterations"
    }
}

/**
 * [EncryptedSharedPreferences] 的工厂,测试时可以替换成内存实现。
 */
interface EncryptedPrefsFactory {
    fun open(): SharedPreferences

    class Default(private val context: Context) : EncryptedPrefsFactory {
        override fun open(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "accountbook_key_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

/**
 * Android Keystore 的抽象层,这样 [KeyVault] 的核心逻辑能在 JVM 单测里跑。
 */
interface KeystoreAccess {

    /** 当前在用的生物识别 AES 密钥别名。 */
    val biometricKeyAlias: String

    /** 创建一条"要求用户认证"的 AES-256-GCM Keystore 密钥,返回它的句柄。 */
    fun ensureBiometricKey(): SecretKey

    /** 只创建 + 返回别名,不在外部打开 key handle。给 [KeyVault.reenrollBiometric] 用。 */
    fun ensureBiometricKeyAlias(): String

    /** 加载已存在的 Keystore 密钥。失败就抛 [CryptoException]。 */
    fun loadBiometricKey(alias: String): SecretKey

    fun deleteBiometricKey(alias: String)

    class Default(private val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }) :
        KeystoreAccess {

        override val biometricKeyAlias: String = "accountbook_bio_v1"

        override fun ensureBiometricKey(): SecretKey {
            val existing = keystore.getKey(biometricKeyAlias, null) as? SecretKey
            if (existing != null) return existing
            return AndroidKeystoreKeyFactory.create(biometricKeyAlias)
        }

        override fun ensureBiometricKeyAlias(): String {
            val existing = keystore.getKey(biometricKeyAlias, null)
            if (existing == null) AndroidKeystoreKeyFactory.create(biometricKeyAlias)
            return biometricKeyAlias
        }

        override fun loadBiometricKey(alias: String): SecretKey {
            val key = keystore.getKey(alias, null) as? SecretKey
                ?: throw CryptoException.BlobCorrupted("Keystore 密钥 $alias 不存在")
            return key
        }

        override fun deleteBiometricKey(alias: String) {
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
        }
    }
}
