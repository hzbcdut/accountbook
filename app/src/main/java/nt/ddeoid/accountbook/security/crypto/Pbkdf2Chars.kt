package nt.ddeoid.accountbook.security.crypto

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * `CharArray` → UTF-8 字节,**全程不产生 `String`**。
 *
 * 为什么在意这件事:`String` 是不可变的,一旦创建就没办法主动擦除,会在堆里
 * 留到某次 GC 为止 —— 对 PIN 这种秘密来说是白送的内存残留。`CharArray` 可以
 * `fill(' ')` 擦掉,所以 PIN 从输入框到 KDF 的整条路上都用 `CharArray` 承载。
 *
 * 显式指定 UTF-8(而不是依赖 `PBEKeySpec` 的实现行为)的原因见 [Pbkdf2] 的注释。
 */
internal object Pbkdf2Chars {

    /**
     * 把 [chars] 按 UTF-8 编码成字节。
     *
     * 对畸形输入(孤立代理项,正常文本输入不会产生)用 REPLACE 而不是抛异常:
     * 派生必须是**全函数**,否则用户在恢复界面输入一个奇怪的字符就会拿到一个崩溃
     * 而不是"密码错误"。REPLACE 的行为对同样的输入永远一致,所以派生结果仍然确定。
     */
    fun toUtf8Bytes(chars: CharArray): ByteArray {
        if (chars.isEmpty()) return ByteArray(0)
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = encoder.encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
