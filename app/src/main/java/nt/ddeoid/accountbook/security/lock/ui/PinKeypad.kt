package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nt.ddeoid.accountbook.security.crypto.SecretBytes

/**
 * 自定义数字 PIN 键盘 —— 3×4 网格(1-9 + 0 + 退格),[dots] 上面展示已输入位数。
 *
 * ## 为什么不用系统 InputMethod
 *
 * 数字 PIN 走系统键盘会把所有按键都过 [InputMethodManager],输入法可能记录到"学习输入"
 * 之类的行为里;另一方面,**PIN 输入的字符不应该被任何输入法 / 剪贴板 / 自动填充看到**,
 * 自绘 keypad 是干净的兜底。代价是体验差一点,但对一个 6-8 位数字 PIN 来说可以接受。
 *
 * ## 字符生命周期
 *
 * 用户输入保存在 [buffer] 里(本地 [CharArray]),到 [maxLength] 调 [onSubmit] 提交;
 * 提交时**复制**一份给 onSubmit,然后立刻擦自己那份。这跟 LockController 的"调用方负责 wipe"
 * 契约匹配 —— 拷贝到 caller 的 CharArray 在 onSubmit 调用方那边再被 wipe 一次,中间这一份
 * 一旦回调结束就成 free 的(但 SecretBytes.wipe 会写 0)。
 *
 * ## 不参与 SavedStateHandle
 *
 * Process death 时 [buffer] 跟着 remember 一起消失,用户回到锁屏从 0 重新输入。
 * 这是 Q14=B + Q17=B 的设计:不能有任何"上次输入到第几位"的痕迹留下。
 */
@Composable
fun PinKeypad(
    onSubmit: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 6,
    enabled: Boolean = true,
    busy: Boolean = false,
    showDots: Boolean = true,
) {
    val buffer = remember { CharArray(maxLength) }
    var length by remember { mutableIntStateOf(0) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showDots) {
            Dots(length = length, total = maxLength)
            Spacer(Modifier.height(24.dp))
        }

        // 3×4 grid:1-9 / clear 0 backspace
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "<"),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { key ->
                    KeyButton(
                        label = key,
                        enabled = enabled && !busy && key.isNotEmpty(),
                        onClick = {
                            when (key) {
                                "" -> Unit
                                "<" -> backspace(buffer, length) { length = it }
                                else -> appendDigit(buffer, key[0], length) { newLen ->
                                    if (newLen == maxLength) {
                                        // 提交:复制 buffer 给 caller,清自己的
                                        onSubmit(buffer.copyOf().also { SecretBytes.wipe(buffer) })
                                        length = 0
                                    } else {
                                        length = newLen
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Dots(length: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val filled = i < length
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    if (label.isEmpty()) {
        Spacer(Modifier.size(72.dp))
        return
    }
    if (label == "<") {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "退格")
            }
        }
        return
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
    }
}

private fun appendDigit(buffer: CharArray, digit: Char, currentLen: Int, setLen: (Int) -> Unit) {
    buffer[currentLen] = digit
    setLen(currentLen + 1)
}

private fun backspace(buffer: CharArray, currentLen: Int, setLen: (Int) -> Unit) {
    if (currentLen == 0) return
    buffer[currentLen - 1] = ' '   // wipe 一格,留个痕迹比留原字符好
    setLen(currentLen - 1)
}
