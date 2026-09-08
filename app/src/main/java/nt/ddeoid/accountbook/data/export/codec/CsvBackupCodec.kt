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
 * id,platform,account,account_type,registered_at,notes,is_active,created_at,updated_at,tags
 * ```
 *
 * - `tags` 列是分号分隔的 tag 名,导入时按名字回查本地 tag 表,找不到的 tag 静默丢弃。
 * - `notes` 中可能包含逗号/换行/分号,统一用 `"` 包裹 + CSV 标准转义(`""` -> `"`)。
 * - 头一行是表头;没有 metadata 头(那是 JSON 的职责);导入端靠列数判定。
 *
 * 这是为 Excel 用户做的"看得见就行"的格式,完整性以 JSON 为准。
 */
object CsvBackupCodec {

    private const val HEADER = "id,platform,account,account_type,registered_at,notes,is_active,created_at,updated_at,tags"

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
            writer.append(escape(acc.tagIds.joinToString(";")))
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
            // 容错:列数对不上就跳过这行
            if (fields.size < 10) continue
            val accountType = runCatching { AccountType.valueOf(fields[3]) }.getOrNull() ?: continue
            val tagIds = fields[9].split(';').filter { it.isNotBlank() }
            rows += AccountExport(
                id = fields[0],
                platform = fields[1],
                account = fields[2],
                accountType = accountType,
                registeredAt = fields[4].takeIf { it.isNotBlank() },
                notes = fields[5],
                isActive = fields[6].equals("true", ignoreCase = true),
                createdAt = fields[7].toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = fields[8].toLongOrNull() ?: System.currentTimeMillis(),
                tagIds = tagIds,
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
