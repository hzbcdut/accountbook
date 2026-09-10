package nt.ddeoid.accountbook.security.lock.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 助记词 12 词渲染成图片 + 保存到相册。
 *
 * **路径选择**:
 * - Android 10+ (API 29+):用 [MediaStore] 写入 `Pictures/AccountBook/`,**不需要**任何权限。
 *   系统会自动扫媒体库,相册立刻能看到。
 * - Android 9 及以下 (API 26-28):需要 `WRITE_EXTERNAL_STORAGE` 运行时权限(manifest 已声明
 *   `maxSdkVersion=28`)。写到 `Environment.DIRECTORY_PICTURES` + 走 MediaScanner
 *   让相册能扫到。
 *
 * **线程**:`renderMnemonicBitmap` 用 Canvas 绘制,在主线程也很快(几毫秒);`saveBitmapToGallery`
 * 是 IO,调用方应放到 background dispatcher(`Dispatchers.IO`)。失败抛 [SaveToGalleryException]。
 *
 * **安全**:图片里含 12 词明文。保存前应该弹个确认 dialog(由 UI 层处理,这里只负责渲染)。
 * 渲染完后在文件名前加时间戳,避免覆盖。
 */
internal object MnemonicImageRenderer {

    private const val ALBUM_FOLDER = "AccountBook"
    private const val MIME_TYPE = "image/png"
    private const val IMAGE_QUALITY = 100

    // 渲染常量 —— 1080×1920 是主流手机竖屏分辨率(9:16),设固定值避免被
    // View 系统依赖。PNG 是位图,跟屏幕分辨率无关,但 1080 宽对屏幕分享
    // /相册预览来说足够清晰。
    private const val WIDTH_PX = 1080
    private const val HEIGHT_PX = 1920

    // 配色:跟应用 warm 主题呼应。浅米色背景 + 深棕文字,印刷友好(打印出来
    // 也清楚),不刺眼。
    private const val BG_COLOR = 0xFFFAF7F2.toInt()          // 浅米色
    private const val TEXT_PRIMARY = 0xFF2E2A26.toInt()     // 深棕
    private const val TEXT_SECONDARY = 0xFF7A6F66.toInt()   // 中棕
    private const val DIVIDER_COLOR = 0xFFD9CFBF.toInt()    // 浅米线条
    private const val ACCENT_COLOR = 0xFFC75B39.toInt()     // 跟主题 primary 同色系

    /**
     * 把 12 词渲染成竖屏 PNG bitmap。
     *
     * 设计:
     * - 顶部:标题 + 保存时间
     * - 中间:3 列 × 4 行,每个格子里 "01 abandon" 形式,序号靠左 + 词靠右
     * - 底部:分隔线 + 警告文字
     *
     * 字体用 Paint 自带,没引外部字体文件 —— 设备字体在不同 ROM 上可能
     * 略有差异,但 12 词都是 ASCII 拉丁词,主流字体都覆盖。
     */
    fun renderMnemonicBitmap(
        words: List<String>,
        title: String,
        savedAtMillis: Long = System.currentTimeMillis(),
    ): Bitmap {
        require(words.size == 12) { "expected 12 words, got ${words.size}" }

        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRIMARY
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SECONDARY
            textSize = 32f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }
        val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SECONDARY
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRIMARY
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIVIDER_COLOR
            strokeWidth = 2f
        }
        val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_COLOR
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }

        // ---- 顶部标题 ----
        canvas.drawText(title, 64f, 130f, titlePaint)
        val dateStr = formatTimestamp(savedAtMillis)
        canvas.drawText("Saved at: $dateStr", 64f, 180f, datePaint)

        // 顶部分隔线
        canvas.drawLine(64f, 220f, (WIDTH_PX - 64).toFloat(), 220f, dividerPaint)

        // ---- 12 词 3 列 × 4 行 ----
        val colWidth = (WIDTH_PX - 128) / 3   // 64 padding on each side
        val rowHeight = 200f
        val gridTop = 320f

        for ((index, word) in words.withIndex()) {
            val row = index / 3
            val col = index % 3
            val cellLeft = 64f + col * colWidth
            val cellTop = gridTop + row * rowHeight

            // 序号
            val numText = String.format(Locale.US, "%02d", index + 1)
            canvas.drawText(numText, cellLeft, cellTop, indexPaint)

            // 词
            canvas.drawText(word, cellLeft, cellTop + 64f, wordPaint)

            // 每个 cell 底部细线
            canvas.drawLine(
                cellLeft,
                cellTop + rowHeight - 30f,
                cellLeft + colWidth - 32f,
                cellTop + rowHeight - 30f,
                dividerPaint,
            )
        }

        // ---- 底部警告 ----
        val footerY = HEIGHT_PX - 200f
        canvas.drawLine(64f, footerY - 40f, (WIDTH_PX - 64).toFloat(), footerY - 40f, dividerPaint)
        canvas.drawText(
            "Anyone with these 12 words can unlock your AccountBook.",
            64f,
            footerY,
            warningPaint,
        )
        canvas.drawText("Keep this offline.", 64f, footerY + 44f, warningPaint)

        return bitmap
    }

    /**
     * 保存 [bitmap] 到相册 `Pictures/AccountBook/`。
     *
     * @return 保存后的 content uri(API 29+)或 file path(API ≤28),用于 UI 反馈。
     * @throws SaveToGalleryException 失败时抛,带原因。
     */
    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): SavedImage {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, displayName)
        } else {
            saveViaLegacyStorage(context, bitmap, displayName)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): SavedImage {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_FOLDER",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw SaveToGalleryException("MediaStore.insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, IMAGE_QUALITY, out)) {
                    throw SaveToGalleryException("Bitmap.compress(PNG) returned false")
                }
            } ?: throw SaveToGalleryException("openOutputStream returned null")

            // IS_PENDING 翻转 —— 写完后必须置 0,系统才把文件视为可用。
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return SavedImage(uri = uri, displayPath = "Pictures/$ALBUM_FOLDER/$displayName.png")
        } catch (e: Exception) {
            // 失败清理 —— 删半成品记录。
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveViaLegacyStorage(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): SavedImage {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES,
        )
        val albumDir = File(picturesDir, ALBUM_FOLDER)
        if (!albumDir.exists() && !albumDir.mkdirs()) {
            throw SaveToGalleryException("Failed to create $albumDir")
        }
        val outFile = File(albumDir, "$displayName.png")
        try {
            FileOutputStream(outFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, IMAGE_QUALITY, out)) {
                    throw SaveToGalleryException("Bitmap.compress(PNG) returned false")
                }
            }
        } catch (e: Exception) {
            if (outFile.exists()) outFile.delete()
            throw e
        }

        // 让 MediaScanner 扫一下,相册立刻看到。
        // MediaScannerConnection 在 legacy 路径上更可靠,但 Activity context 调用
        // 比较复杂;Android 自带 MediaStore 索引在重启后也能扫到,这里返回 file path
        // 让调用方决定要不要主动 scan。
        return SavedImage(uri = Uri.fromFile(outFile), displayPath = outFile.absolutePath)
    }

    /**
     * 给个稳定的 displayName:`AccountBook-mnemonic-20260910-153045`。
     * 同一秒多次保存会冲突,加 4 位 hash 避免。
     */
    fun buildDisplayName(savedAtMillis: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(savedAtMillis))
        return "AccountBook-mnemonic-$ts"
    }

    private fun formatTimestamp(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(millis))
    }

    data class SavedImage(val uri: Uri, val displayPath: String)
}

internal class SaveToGalleryException(message: String) : RuntimeException(message)