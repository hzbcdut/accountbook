package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别 / 设备凭据认证的薄包装。
 *
 * ## 设计动机
 *
 * BiometricPrompt 必须在 [FragmentActivity] 上弹(基类要有 FragmentManager)。
 * Compose 里直接拿 `LocalContext.current as FragmentActivity` 在调用方那块做转换,
 * 重复 + 容易出错 —— 集中到这里:
 * - Composable 用 [rememberBiometricGate] 拿一个实例
 * - ViewModel 用 [prompt] 时是 suspend,跟 controller 的其它异步路径一致
 *
 * ## Step 6 状态
 *
 * 当前**只**有接口形态 —— 真实实现留 Step 10。
 *
 * Step 6 关注的是 LockScreen 的 PIN 路径;生物识别按钮可见但点了之后只走到 ViewModel,
 * ViewModel 在拿不到真实 gate 时传 [FakeBiometricGate] 的替身(测试用)。
 */
interface BiometricGate {

    suspend fun prompt(): Result

    sealed interface Result {
        data object Success : Result
        data class Failed(val cause: Throwable) : Result
        data object Cancelled : Result
        /** 用户没 enroll / Keystore 失效 / 设备没硬件 —— UI 把按钮藏起来。 */
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
 * Step 10 填这个。当前抛 UnsupportedOperationException —— Step 6 跑通后 LockScreen
 * 不会调用它(测试用 FakeBiometricGate),Instrumented e2E 在 Phase 5 才会触发。
 */
private class RealBiometricGate(private val activity: FragmentActivity) : BiometricGate {
    override suspend fun prompt(): BiometricGate.Result =
        throw UnsupportedOperationException(
            "BiometricGate 真实实现见 Step 10;当前 Step 6 阶段此路径不会跑到",
        )
}
