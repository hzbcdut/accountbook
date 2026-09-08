package nt.ddeoid.accountbook.data.export.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import nt.ddeoid.accountbook.data.export.model.AccountBookBackup

/**
 * AccountBook 备份的 JSON 编解码。
 *
 * - `prettyPrint = true` 给用户看的可读 JSON。
 * - `ignoreUnknownKeys = true` 兼容前向扩展(以后加字段,旧版本仍能读)。
 * - 输出 / 解析都强制带 UTF-8。
 */
object JsonBackupCodec {

    val formatter: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: AccountBookBackup): String =
        formatter.encodeToString(AccountBookBackup.serializer(), backup)

    /** 解析失败抛 [kotlinx.serialization.SerializationException];UI 自行 catch。 */
    fun decode(text: String): AccountBookBackup =
        formatter.decodeFromString(AccountBookBackup.serializer(), text)

    /** MIME + 文件后缀常量,给 SAF 用。 */
    const val MIME_TYPE: String = "application/json"
    const val FILE_EXTENSION: String = "json"
}
