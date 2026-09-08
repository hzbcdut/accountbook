package nt.ddeoid.accountbook.security.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * 把账号复制到剪贴板的加固版本。
 *
 * ## 三件事
 *
 * 1. **标记为敏感**:Android 13+ 通过 [ClipDescription.EXTRA_IS_SENSITIVE] 让系统不在
 *    编辑器里预览粘贴内容(防止肩膀偷窥)。
 * 2. **60 秒后自动清空** —— 降低"复制完忘了清"的窗口期。其他 app 拿不到 `EXTRA_IS_SENSITIVE`
 *    内容也无所谓,自动清空是更硬的兜底。
 * 3. **清除上次残留**:复制新内容之前先 clearPrimaryClip(),避免新内容和旧内容同时存在。
 *
 * ## 为什么不用 FLAG_SECURE
 *
 * `WindowManager.LayoutParams.FLAG_SECURE` 阻止整个 window 截图,**已经**在 MainActivity
 * 启用 —— 第三方 app 看不到我们的内容。但剪贴板是**跨进程**的,FLAG_SECURE 管不到。
 * 所以走"标记敏感 + 自动清空"两条路。
 *
 * ## 自动清空不会"误杀"
 *
 * 用户复制完账号,打开微信,准备粘贴 —— 60 秒内一定粘完。如果他在 60 秒内**复制了别的
 * 内容**(比如浏览器复制了一段 URL),自动清空会先把我们的敏感内容清掉,再被 URL
 * 覆盖。这种 race 没问题:我们不主动删 URL,只是清掉自己的。
 */
object SensitiveClipboard {

    /**
     * 默认清空延迟:60 秒。够用户"复制 → 切 app → 粘贴"整个流程,又短到能让"复制完
     * 忘了清"窗口期可控。
     */
    const val DEFAULT_AUTO_CLEAR_DELAY_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 保存最近一次清空的 runnable,以便"复制新内容前"先取消(避免自动清空先跑掉)。 */
    private var pendingClear: Runnable? = null

    /**
     * 复制 [text] 到系统剪贴板,并按 [autoClearDelayMs] 后自动清空。
     *
     * 调用方不需要关心清空逻辑 —— 本类负责调度。[delayMs] 传 0 表示不自动清空
     * (不推荐;暴露给测试用)。
     */
    @JvmStatic
    @JvmOverloads
    fun copy(
        context: Context,
        text: String,
        label: String = "AccountBook",
        delayMs: Long = DEFAULT_AUTO_CLEAR_DELAY_MS,
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        // 取消前一次还没跑的清空,避免旧 runnable 把新内容清掉
        pendingClear?.let { mainHandler.removeCallbacks(it) }
        // 清掉当前剪贴板里残留的内容 —— 防止旧内容(包括我们自己之前留下的)还在
        try {
            cm.clearPrimaryClip()
        } catch (_: Throwable) {
            // Android 13+ 某些场景下 clearPrimaryClip 抛 SecurityException,吞掉
        }
        val clip = ClipData.newPlainText(label, text).markSensitive()
        cm.setPrimaryClip(clip)
        if (delayMs > 0) {
            val clearRunnable = object : Runnable {
                override fun run() {
                    // 二次确认 —— 如果剪贴板当前内容还是"我们之前放进去的 label",
                    // 就清掉;不是(用户已经复制了别的内容)就跳过,不要误伤。
                    val current = cm.primaryClip
                    if (current != null && current.description?.label == label) {
                        try {
                            cm.clearPrimaryClip()
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                    if (pendingClear === this) pendingClear = null
                }
            }
            pendingClear = clearRunnable
            mainHandler.postDelayed(clearRunnable, delayMs)
        }
    }

    /** 立即清空(用于锁屏时主动 wipe —— 防止"用户复制完账号后立即锁屏,内容残留 60s")。 */
    @JvmStatic
    fun clearNow(context: Context) {
        pendingClear?.let { mainHandler.removeCallbacks(it) }
        pendingClear = null
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        try {
            cm.clearPrimaryClip()
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun ClipData.markSensitive(): ClipData {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+:用 description.extras 直接加 EXTRA_IS_SENSITIVE
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        // Android 12- 没法标记;依赖"60 秒自动清空"兜底
        return this
    }
}
