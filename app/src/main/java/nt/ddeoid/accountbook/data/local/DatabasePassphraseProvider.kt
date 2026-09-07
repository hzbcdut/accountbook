package nt.ddeoid.accountbook.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Room 数据库加密口令的提供者。
 *
 * Phase 1 占位:首次启动时随机生成 32 字节的口令,存入 EncryptedSharedPreferences。
 * Phase 4 会替换为"用户 PIN + Android Keystore 主密钥"的派生方案,以及"助记词 → 口令"的二次校验路径。
 *
 * ⚠️ Phase 1 的实现不满足"可恢复"——丢了 EncryptedSharedPreferences 就解锁不了 DB。
 * 在 Phase 4 引入助记词之前,不要把这版逻辑推到真实用户面前。
 */
class DatabasePassphraseProvider(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "accountbook_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 获取或创建 DB 口令。返回 32 字节原文(由 SQLCipher 直接使用)。 */
    fun getOrCreate(): ByteArray {
        val existing = prefs.getString(KEY_DB_PASSPHRASE_B64, null)
        if (existing != null) {
            return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        }
        val fresh = ByteArray(PASSPHRASE_LENGTH).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE_B64, android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP))
            .apply()
        return fresh
    }

    /** Phase 4 用:用户主动清空数据时,清掉口令让 DB 文件变成随机噪声。 */
    fun wipe() {
        prefs.edit().remove(KEY_DB_PASSPHRASE_B64).apply()
    }

    companion object {
        private const val PASSPHRASE_LENGTH = 32
        private const val KEY_DB_PASSPHRASE_B64 = "db_passphrase_b64"
    }
}