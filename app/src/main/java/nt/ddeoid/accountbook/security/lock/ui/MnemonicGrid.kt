package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec

/**
 * 12 格助记词输入网格。
 *
 * 每个格子是一个独立 [OutlinedTextField];`remember { mutableStateOf("") }` 持有该格的
 * 字符串,**不在 ViewModel 中** —— ViewModel 只看规范化后的值。这一层只管 UI 形态,
 * 不做校验(校验在 ViewModel [RecoveryViewModel.onWordChanged] 里通过 [MnemonicCodec.normalize] +
 * [MnemonicCodec.wordSet] 判定)。
 *
 * ## 为什么不做实时 autocomplete dropdown
 *
 * 完整版要监听 4 词前缀 + dropdown 列表滚动。Phase 5 才接;这里只做"框" —— 用户
 * 输完 12 词后按"恢复"按钮,由 [MnemonicCodec.decode] 一次性给 ChecksumMismatch
 * 或 UnknownWord 反馈。够用。
 */
@Composable
fun MnemonicGrid(
    onWordChanged: (position: Int, raw: String) -> Unit,
    wordValidInList: Map<Int, Boolean>,
    modifier: Modifier = Modifier,
) {
    // 12 个独立 string state —— process death 全丢。这是 Q14+B + Q17+B 的设计。
    val fields = remember { Array(RecoveryViewModel.WORD_COUNT) { mutableStateOf("") } }

    Column(modifier = modifier) {
        for (i in 0 until RecoveryViewModel.WORD_COUNT) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = (i + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(28.dp),
                )
                OutlinedTextField(
                    value = fields[i].value,
                    onValueChange = { raw ->
                        fields[i].value = raw
                        onWordChanged(i, raw)
                    },
                    singleLine = true,
                    isError = wordValidInList[i] == false && fields[i].value.isNotBlank(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                        autoCorrect = false,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
