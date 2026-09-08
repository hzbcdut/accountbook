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
    private val entropySource: EntropySource = EntropySource(),
    private val wrapper: KeyWrapper = KeyWrapper(entropySource),
    private val pinKdfIterations: Int = PinKdf.PRODUCTION_ITERATIONS,
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
     * @return [SetupResult] 包含 master key handle + 12 词,**调用方必须在抄写完
     *   助记词并展示结束后擦除 handle**。
     * @throws IllegalStateException 已经初始化过
     */
    fun initialize(pin: CharArray, mnemonicCodec: MnemonicCodec): SetupResult {
        check(!isInitialized()) { "KeyVault 已经初始化,不要重复调用 initialize" }
        val salt = entropySource.nextSalt()
        val pinKey = PinKdf.derive(pin, salt, pinKdfIterations)
        val bioKey = keystoreAccess.ensureBiometricKey()
        try {
            val generated = MasterKeyFactory.generate(mnemonicCodec, entropySource)
            try {
                // 熵是持久化层的"主语":wrap 它、存它、助记词也是它(Q10=A)。
                // masterKey 在这里只是给 SetupResult 顺路带回去,不在 blob 里出现。
                val entropy = generated.entropy
                val pinBlob = SecretBytes(entropy.copyOf()).use { copyForPin ->
                    wrapper.wrap(pinKey.bytes, copyForPin, KeyWrapper.WrapContext.PIN)
                }
                val bioBlob = SecretBytes(entropy.copyOf()).use { copyForBio ->
                    wrapper.wrap(bioKey, copyForBio, KeyWrapper.WrapContext.BIOMETRIC)
                }
                withPrefs { prefs ->
                    prefs.edit()
                        .putString(KEY_MASTER_ENTROPY, base64(entropy))
                        .putString(KEY_PIN_SALT, base64(salt))
                        .putInt(KEY_KDF_ITERATIONS, pinKdfIterations)
                        .putString(KEY_PIN_BLOB, pinBlob)
                        .putString(KEY_BIO_BLOB, bioBlob)
                        .putString(KEY_BIO_KEY_ALIAS, keystoreAccess.biometricKeyAlias)
                        .apply()
                }
                // 熵已经写进 prefs,可以擦了
                entropy.fill(0)
                return SetupResult(
                    masterKey = generated.masterKey,
                    mnemonic = generated.mnemonic,
                )
            } catch (t: Throwable) {
                generated.masterKey.wipe()
                generated.entropy.fill(0)
                throw t
            }
        } finally {
            pinKey.wipe()
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
