package nt.ddeoid.accountbook.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** `registered_at` 的存储 / 显示格式:`yyyy-MM-dd`,本地时区。 */
private val registeredDateFormatter: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

fun formatRegisteredDate(epochMillis: Long): String =
    registeredDateFormatter.format(Date(epochMillis))

/** 解析 `yyyy-MM-dd`,失败返回 null。 */
fun parseRegisteredDate(value: String): Long? = runCatching {
    registeredDateFormatter.parse(value.trim())?.time
}.getOrNull()

/** `created_at` / `updated_at` 的可读时间。 */
private val timestampFormatter: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

fun formatTimestamp(epochMillis: Long): String =
    if (epochMillis <= 0L) "—" else timestampFormatter.format(Date(epochMillis))
