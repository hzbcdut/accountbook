package nt.ddeoid.accountbook.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags

/**
 * 一个平台分组的卡片:头部 `平台 (N)` + 下方一组 [AccountListItem]。
 *
 * Phase 2 默认全部展开;折叠/展开 Phase 6 再加。
 */
@Composable
fun AccountGroupCard(
    platform: String,
    items: List<AccountWithTags>,
    onItemClick: (String) -> Unit,
    onItemLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = platform,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = items.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Column(
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { row ->
                    AccountListItem(
                        row = row,
                        onClick = { onItemClick(row.account.id) },
                        onLongPress = { onItemLongPress(row.account.id) },
                    )
                }
            }
        }
    }
}

@Suppress("unused")
private val DefaultContentPadding = PaddingValues(0.dp)