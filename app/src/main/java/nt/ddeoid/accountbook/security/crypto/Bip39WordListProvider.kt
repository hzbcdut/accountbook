package nt.ddeoid.accountbook.security.crypto

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import nt.ddeoid.accountbook.security.crypto.Bip39WordList.ASSET_PATH
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 APK assets 加载 BIP39 英文词表并提供 [MnemonicCodec]。
 *
 * 加载是 IO + 校验,放在这里 lazy 一次完成 —— 之后所有依赖 [MnemonicCodec] 的代码都拿到
 * 同一份实例。
 *
 * ⚠️ 单测不要走这条路径,直接 `new MnemonicCodec(words)`。本类的存在只是为了把
 * "从 assets 读 + 校验 + 构造 codec" 这一串步骤集中到 DI 容器。
 */
@Singleton
class Bip39WordListProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val codec: MnemonicCodec by lazy { load() }

    fun codec(): MnemonicCodec = codec

    private fun load(): MnemonicCodec {
        val raw = context.assets.open(ASSET_PATH).use { it.readBytes() }
        val words = Bip39WordList.parseAndVerify(raw)
        return MnemonicCodec(words)
    }
}
