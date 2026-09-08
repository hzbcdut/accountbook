package nt.ddeoid.accountbook.security.crypto

/**
 * PIN / passphrase 的接受策略(Q11=C:6 位数字起步,允许更长的字母数字 passphrase)。
 *
 * 放在 security 包而不是 UI 包,是因为这是**安全策略**而不只是输入校验:UI 换了、
 * 语言换了,这条线不能跟着动。
 *
 * ## 为什么不做"弱口令黑名单"
 *
 * 拦住 `123456` / `000000` 看起来很负责,实际上对离线爆破**没有任何帮助** —— 攻击者
 * 本来就会先试这些。黑名单只会给用户一种虚假的安全感,同时把"我的 PIN 为什么不让用"
 * 变成客服问题。强度只来自长度和字符集,所以策略只管这两件事。
 *
 * ## 长度门槛的由来
 *
 * 6 位数字 = 10⁶,配 60 万次 PBKDF2 大约是"一台好显卡跑半天到一天"的量级
 * (详见 [PinKdf] 的强度账)。8 个字符的随机字母数字是 62⁸ ≈ 2⁴⁷,已经不在
 * 现实攻击范围内;所以只要输入里含非数字字符,就要求 8 位起 —— 用长度换熵,
 * 而不是用一个更复杂的规则去解释"什么算强"。
 */
object PinPolicy {

    /** 纯数字 PIN 的最小长度。 */
    const val MIN_DIGIT_PIN_LENGTH = 6

    /** 含字母/符号的 passphrase 最小长度。 */
    const val MIN_PASSPHRASE_LENGTH = 8

    /** 上限。再长对 PBKDF2 没有额外收益,只是让输入变痛苦。 */
    const val MAX_LENGTH = 128

    fun validate(secret: CharSequence): Result {
        if (secret.isEmpty()) return Result.Empty
        if (secret.length > MAX_LENGTH) return Result.TooLong(MAX_LENGTH)

        val digitOnly = secret.all { it.isDigit() }
        val minLength = if (digitOnly) MIN_DIGIT_PIN_LENGTH else MIN_PASSPHRASE_LENGTH
        if (secret.length < minLength) {
            return Result.TooShort(required = minLength, digitOnly = digitOnly)
        }
        return Result.Accepted(digitOnly = digitOnly)
    }

    sealed interface Result {
        /** 通过。[digitOnly] 为真表示这是纯数字 PIN,UI 可以据此决定键盘类型。 */
        data class Accepted(val digitOnly: Boolean) : Result

        /** 空输入。跟"太短"分开,因为提示文案不一样。 */
        data object Empty : Result

        data class TooShort(val required: Int, val digitOnly: Boolean) : Result

        data class TooLong(val max: Int) : Result
    }
}
