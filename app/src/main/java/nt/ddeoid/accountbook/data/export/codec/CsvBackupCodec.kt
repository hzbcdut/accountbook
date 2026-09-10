package nt.ddeoid.accountbook.data.export.codec

import nt.ddeoid.accountbook.data.export.model.AccountBookBackup
import nt.ddeoid.accountbook.data.export.model.AccountExport
import nt.ddeoid.accountbook.data.local.entity.AccountType
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer
import java.io.StringReader
import java.io.StringWriter

/**
 * AccountBook 备份的 CSV 编解码 —— **只覆盖 accounts 表**。
 *
 * 每一行字段顺序固定:
 *
 * ```
 * id,platform,account,account_type,registered_at,notes,is_active,created_at,updated_at,tags,password
 * ```
 *
 * - `tags` 列是分号分隔的 tag 名,导入时按名字回查本地 tag 表,找不到的 tag 静默丢弃。
 * - `password` 列(v0.5.0 新增)在 tags 之后;旧版 CSV 没有这一列,导入端按列数缺列处理 → null。
 * - `notes` / `password` 中可能包含逗号/换行/分号,统一用 `"` 包裹 + CSV 标准转义(`""` -> `"`)。
 * - 头一行是表头;没有 metadata 头(那是 JSON 的职责);导入端靠列数判定。
 *
 * 这是为 Excel 用户做的"看得见就行"的格式,完整性以 JSON 为准。
 */
object CsvBackupCodec {

    private const val HEADER = "id,platform,account,account_type,registered_at,notes,is_active,created_at,updated_at,tags,password"

    /** 列数下标。`password` 在 tags 之后,新增时如果改顺序要同步更新这里的下标。 */
    private const val COL_ID = 0
    private const val COL_PLATFORM = 1
    private const val COL_ACCOUNT = 2
    private const val COL_ACCOUNT_TYPE = 3
    private const val COL_REGISTERED_AT = 4
    private const val COL_NOTES = 5
    private const val COL_IS_ACTIVE = 6
    private const val COL_CREATED_AT = 7
    private const val COL_UPDATED_AT = 8
    private const val COL_TAGS = 9
    private const val COL_PASSWORD = 10

    /** 旧版 CSV 的列数 = 10(无 password)。缺列时密码取 null,row 仍然写入。 */
    private const val MIN_LEGACY_COLUMNS = 10

    /** 把已有 backup 里的 accounts 段写成 CSV。 */
    fun encode(backup: AccountBookBackup): String {
        val w = StringWriter()
        writeRows(backup.accounts, w)
        return w.toString()
    }

    /** 把纯 CSV 文本解析回 backup。tagIds 字段留空,由 importer 按名字回查。 */
    fun decode(text: String): AccountBookBackup {
        val rows = readRows(BufferedReader(StringReader(text)))
        return AccountBookBackup(
            exportedAt = 0L,
            accounts = rows,
            tags = emptyList(),
            platforms = emptyList(),
        )
    }

    fun encodeToWriter(backup: AccountBookBackup, writer: Writer) {
        writeRows(backup.accounts, writer)
    }

    fun decodeFromReader(reader: Reader): AccountBookBackup {
        val rows = readRows(BufferedReader(reader))
        return AccountBookBackup(
            exportedAt = 0L,
            accounts = rows,
            tags = emptyList(),
            platforms = emptyList(),
        )
    }

    private fun writeRows(accounts: List<AccountExport>, writer: Writer) {
        writer.appendLine(HEADER)
        for (acc in accounts) {
            writer.append(escape(acc.id)).append(',')
            writer.append(escape(acc.platform)).append(',')
            writer.append(escape(acc.account)).append(',')
            writer.append(acc.accountType.name).append(',')
            writer.append(escape(acc.registeredAt.orEmpty())).append(',')
            writer.append(escape(acc.notes)).append(',')
            writer.append(if (acc.isActive) "true" else "false").append(',')
            writer.append(acc.createdAt.toString()).append(',')
            writer.append(acc.updatedAt.toString()).append(',')
            writer.append(escape(acc.tagIds.joinToString(";"))).append(',')
            writer.append(escape(acc.password.orEmpty()))
            writer.append('\n')
        }
        writer.flush()
    }

    private fun readRows(reader: BufferedReader): List<AccountExport> {
        val rows = mutableListOf<AccountExport>()
        var header = true
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (line.startsWith("#")) continue
            val fields = parseCsvLine(line)
            if (header) {
                header = false
                continue
            }
            // 容错:列数对不上就跳过这行 —— 旧版 CSV 是 10 列,新版是 11 列。
            if (fields.size < MIN_LEGACY_COLUMNS) continue
            val accountType = runCatching { AccountType.valueOf(fields[COL_ACCOUNT_TYPE]) }.getOrNull()
                ?: continue
            val tagIds = fields[COL_TAGS].split(';').filter { it.isNotBlank() }
            // password 在 COL_PASSWORD;旧 CSV 没有这一列 → 越界取 null(向后兼容)。
            val password = fields.getOrNull(COL_PASSWORD)?.takeIf { it.isNotBlank() }
            rows += AccountExport(
                id = fields[COL_ID],
                platform = fields[COL_PLATFORM],
                account = fields[COL_ACCOUNT],
                accountType = accountType,
                registeredAt = fields[COL_REGISTERED_AT].takeIf { it.isNotBlank() },
                notes = fields[COL_NOTES],
                isActive = fields[COL_IS_ACTIVE].equals("true", ignoreCase = true),
                createdAt = fields[COL_CREATED_AT].toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = fields[COL_UPDATED_AT].toLongOrNull() ?: System.currentTimeMillis(),
                tagIds = tagIds,
                password = password,
            )
        }
        return rows
    }

    // --- CSV 标准转义 ----------------------------------------------------------

    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    /** 解析一行 CSV 字段。简化版:支持 `"..."` 包裹与 `""` 转义;不支持字段内换行。 */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i += 2
                    continue
                }
                inQuotes && c == '"' -> {
                    inQuotes = false
                    i++
                    continue
                }
                !inQuotes && c == '"' -> {
                    inQuotes = true
                    i++
                    continue
                }
                !inQuotes && c == ',' -> {
                    fields += sb.toString()
                    sb.setLength(0)
                    i++
                    continue
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        fields += sb.toString()
        return fields
    }

    const val MIME_TYPE: String = "text/csv"
    const val FILE_EXTENSION: String = "csv"
}
