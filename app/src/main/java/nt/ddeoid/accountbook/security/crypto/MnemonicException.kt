package nt.ddeoid.accountbook.security.crypto

/**
 * 助记词编解码错误。
 *
 * 分成具体子类是为了 UI 能给**可操作**的提示 —— 用户抄错一个字母和校验位不对,
 * 是两种完全不同的修正动作。这是个单机单用户 app,不存在"泄露信息给攻击者"的顾虑,
 * 精确报错比模糊报错有价值得多。
 */
sealed class MnemonicException(message: String) : IllegalArgumentException(message) {

    /** 熵长度不合法。BIP39 只允许 128/160/192/224/256 bit。 */
    class WrongEntropySize(val byteSize: Int) : MnemonicException(
        "熵长度非法:$byteSize 字节(BIP39 只接受 16/20/24/28/32 字节)",
    )

    /** 词数不对。BIP39 只允许 12/15/18/21/24 个词。 */
    class WrongWordCount(val count: Int) : MnemonicException(
        "应为 12/15/18/21/24 个词,实际 $count 个",
    )

    /** 某个词不在词表里 —— 通常是抄错或拼错。 */
    class UnknownWord(val word: String, val position: Int) : MnemonicException(
        "第 ${position + 1} 个词 \"$word\" 不在 BIP39 英文词表里",
    )

    /**
     * 校验位不匹配。
     *
     * 意味着这些词**看起来合法**但组合不出有效熵:多半是抄的时候漏了一个词、
     * 顺序调换了、或者有两个词写串了。12 词的情况下随机瞎猜通过校验的概率是 1/16。
     */
    data object ChecksumMismatch : MnemonicException(
        "校验位不匹配:这些词的组合无效(检查是否漏词、顺序错、或抄错了某个词)",
    )
}
