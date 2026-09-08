package nt.ddeoid.accountbook.security.lock.ui

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 生物识别 / 设备凭据认证的薄包装。
 *
 * ## 设计动机
 *
 * [BiometricPrompt] 必须在 [FragmentActivity] 上弹(基类要有 FragmentManager)。
 * Compose 里直接拿 `LocalContext.current as FragmentActivity` 在调用方那块做转换,
 * 重复 + 容易出错 —— 集中到这里:
 *
 * - Composable 用 [rememberBiometricGate] 拿一个实例
 * - ViewModel 用 [prompt] 时是 suspend,跟 controller 的其它异步路径一致
 *
 * ## 鉴权器组合
 *
 * 用 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`(Q12=B 决议)—— 不允许 BIOMETRIC_WEAK
 * (Class 2),因为我们靠这把生物识别去开 Keystore 里的 `setUserAuthenticationRequired`
 * 密钥,Class 2 强度不够,Android Keystore 会拒绝绑定 weak 密钥。
 *
 * DEVICE_CREDENTIAL 兜底:用户没 enroll 生物识别时,系统 PIN / 图案 / 密码也能解。
 * Compose UI 端看不到差别 —— 都是同一条"成功"路径,后续 [LockController.unlockWithBiometric]
 * 拿 Keystore SecretKey 解锁。
 *
 * ## suspendCancellableCoroutine 而非 Flow
 *
 * BiometricPrompt 的回调是单次的(`onAuthenticationSucceeded` 只调一次),
 * 用 suspendCancellableCoroutine 最贴切。Flow 反而要 collect + take(1),麻烦。
 * cancel 时 `prompt.cancelAuthentication()` —— 关键:用户退出 LockScreen 时不能
 * 留下一个"弹着"的系统弹窗。
 */
interface BiometricGate {

    suspend fun prompt(): Result

    sealed interface Result {
        /** 鉴权通过,SecretKey 可以用了。 */
        data object Success : Result
        /** 鉴权本身失败(lockout、no identity 等)。[cause] 透传给 [LockError.from]。 */
        data class Failed(val cause: Throwable) : Result
        /** 用户主动取消(back 键 / 弹窗上的取消按钮)。不计错。 */
        data object Cancelled : Result
        /**
         * 设备没硬件 / 没 enroll / 鉴权被管理员禁用 —— UI 把按钮藏起来。
         *
         * **不**跟 [Failed] 合并:这条路径在 UI 上有独立处理(直接隐藏按钮 + 提示用户去
         * 系统设置),跟"试了一次失败"是两件事。
         */
        data object NoHardware : Result
    }
}

/**
 * Composable 端的工厂。
 *
 * @param activityOverride 测试时可以传 mock;默认从 [LocalContext] 强转 FragmentActivity。
 *   强转失败意味着开发者把 BiometricGate 用在了非 FragmentActivity 的宿主里 —— Compose
 *   编译期抓不到这个错误,所以这里用 [checkNotNull] 立刻抛。
 */
@Composable
fun rememberBiometricGate(activityOverride: FragmentActivity? = null): BiometricGate {
    val ctx = LocalContext.current
    val activity = activityOverride ?: (ctx as? FragmentActivity) ?: error(
        "BiometricGate 需要 FragmentActivity 宿主;当前 context=${ctx::class.java.name}"
    )
    return remember(activity) { RealBiometricGate(activity) }
}

/**
 * 真实实现。
 *
 * 流程:
 * 1. [BiometricManager.canAuthenticate] 检硬件 / enroll / 状态
 * 2. 不通过 → [Result.NoHardware]
 * 3. 通过 → 构造 [BiometricPrompt] + [PromptInfo] → [BiometricPrompt.authenticate]
 * 4. 回调里 resume:
 *    - `onAuthenticationSucceeded` → [Result.Success]
 *    - `onAuthenticationError`:
 *      - USER_CANCELED / NEGATIVE_BUTTON / CANCELED → [Result.Cancelled]
 *      - NO_BIOMETRICS / NO_DEVICE_CREDENTIAL / NO_HARDWARE / HW_UNAVAILABLE →
 *        [Result.NoHardware]("走到这一步说明 canAuthenticate 说能、点开后变不能")
 *      - LOCKOUT / LOCKOUT_PERMANENT → [Result.Failed] 透传(后续 LockError.from 转文案)
 *      - 其他 → [Result.Failed] 透传
 * 5. coroutine cancel → 调 [BiometricPrompt.cancelAuthentication] 收起系统弹窗
 */
private class RealBiometricGate(
    private val activity: FragmentActivity,
) : BiometricGate {

    override suspend fun prompt(): BiometricGate.Result {
        val allowedAuthenticators = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        val manager = BiometricManager.from(activity)
        val canAuth = manager.canAuthenticate(allowedAuthenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.i(TAG, "BiometricPrompt 不可用:canAuthenticate=$canAuth")
            return BiometricGate.Result.NoHardware
        }

        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BiometricGate.Result.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> BiometricGate.Result.Cancelled
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_NO_SPACE,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> {
                            Log.w(TAG, "canAuthenticate 通过但弹窗报 $errorCode:$errString")
                            BiometricGate.Result.NoHardware
                        }
                        else -> BiometricGate.Result.Failed(
                            BiometricGateException(errorCode, errString.toString()),
                        )
                    }
                    cont.resume(outcome)
                }

                override fun onAuthenticationFailed() {
                    // 用户输错一次(指纹位置不对、PIN 输错等) —— BiometricPrompt 自己
                    // 会在弹窗里给"再试一次"提示,**不**调 onAuthenticationError,只调
                    // 这个 onAuthenticationFailed。我们不主动 resume,等用户再试或取消。
                    Log.d(TAG, "onAuthenticationFailed —— 用户可能输错,等待系统重试或取消")
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setAllowedAuthenticators(allowedAuthenticators)
                .setTitle(activity.getString(nt.ddeoid.accountbook.R.string.biometric_title))
                .setSubtitle(activity.getString(nt.ddeoid.accountbook.R.string.biometric_subtitle))
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        const val TAG = "BiometricGate"
    }
}

/**
 * BiometricPrompt 回调里那些被归为"鉴权失败"但不重复弹窗的 error code 透传出来的
 * 异常。`code` 留着以便后续按 code 区分;`message` 是系统给的本地化字符串。
 */
class BiometricGateException(
    val code: Int,
    message: String,
) : RuntimeException("BiometricPrompt error $code: $message")
