package nt.ddeoid.accountbook.security.lock

/**
 * 应用锁层面的运行时异常。
 *
 * 跟 [nt.ddeoid.accountbook.security.crypto.CryptoException] 不一样:那些是"密码学层
 * 失败"(tag 校验失败、blob 损坏),[LockException] 是"业务语义失败"(冷却期、未知)。
 * UI 层把这两个家族分别映射成不同的文案。
 */
sealed class LockException(message: String) : RuntimeException(message) {

    /**
     * PIN 解锁被冷却拦截 —— 调用方应该等待 [retryAfterMs] 毫秒再试。
     *
     * Phase 4 #31 节流策略(用户确认):5 次错误 → 30s,10 → 60s,15 → 120s,20 → 240s,
     * 25+ → 300s(封顶)。进程内即可,杀进程绕过 —— 文档化的限制。
     */
    data class InCooldown(val retryAfterMs: Long) :
        LockException("PIN 解锁冷却中,${retryAfterMs}ms 后可重试")
}
