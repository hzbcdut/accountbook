package nt.ddeoid.accountbook.security.crypto

/**
 * 一段需要**主动擦除**的敏感字节。
 *
 * JVM 上 [ByteArray] 一旦分配就没法保证什么时候被回收:明文密钥可能在堆里(以及 swap、
 * core dump 里)残留很久,远超过它还有用的时间。[SecretBytes] 把这件事从"靠自觉"变成
 * "靠结构"—— 包一层、用 `use {}`,想漏掉擦除反而要专门绕开。
 *
 * 这是 Q4=C 决议("关库 + 清 key + 显式擦除内存")的落地工具。
 *
 * ```
 * SecretBytes(masterKey).use { secret ->
 *     database.open(secret.bytes)
 * }
 * // 出了这个块,secret.bytes 已经全 0
 * ```
 *
 * 擦除是尽力而为,值得把边界说清楚:任何复制过这段字节的东西([ByteArray.copyOf])都
 * 留着自己的副本;交给不受我们控制的 native 代码也一样 —— SQLCipher 会把口令拷进它
 * 自己的内存。所以约定是:**master key 只在 [SecretBytes] 里流转,交给 SQLCipher 的
 * 那一刻是最后一次复制**。
 */
class SecretBytes(val bytes: ByteArray) : AutoCloseable {

    @Volatile
    private var wiped = false

    /** 内容是否已清零。擦除之后再读 [bytes] 拿到的全是 0。 */
    val isWiped: Boolean get() = wiped

    /** 把内容清零。可以重复调用。 */
    fun wipe() {
        if (!wiped) {
            bytes.fill(0)
            wiped = true
        }
    }

    override fun close() = wipe()

    /** 复制一份独立的副本,擦除副本是调用方的责任。 */
    fun copy(): SecretBytes = SecretBytes(bytes.copyOf())

    /**
     * 跑一段代码,无论成功失败都 [wipe]。
     *
     * 提供这个 inline 函数是为了让"用完即擦"在调用处看起来自然:`SecretBytes(x).use { ... }`,
     * 而不是 try / finally 一坨。
     */
    inline fun <T> use(block: (SecretBytes) -> T): T {
        try {
            return block(this)
        } finally {
            wipe()
        }
    }

    override fun toString(): String =
        if (wiped) "SecretBytes(wiped)" else "SecretBytes(${bytes.size} bytes, hidden)"

    companion object {
        /**
         * 擦除一段 [CharArray] —— 通常是 PIN 输入框的文本。
         *
         * 这里**故意不提供** `fromUtf8(CharSequence)` 这种便利方法:那种方法必须先造一个
         * 不可变的 `String`,而 `String` 擦不掉,等于把这个类存在的意义悄悄作废了。
         * 要把字符变成字节请走 [Pbkdf2Chars.toUtf8Bytes],它全程只用 `CharArray`。
         */
        fun wipe(chars: CharArray) {
            chars.fill(0.toChar())
        }
    }
}
