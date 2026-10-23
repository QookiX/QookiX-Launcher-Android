package com.zhayi.qookix.compose.ui

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R

/**
 * 「新建」类页面的公共组件。
 *
 * ## 为什么要有这个文件（2026-10-18）
 *
 * 用户的原始反馈：「你这个 UI 似乎和创建实例那里不是同一套，你不能复用吗」。
 *
 * 他说得对。`CreateInstancePage.kt` 里本来就有 `FieldLabel` / `VersionRow`，
 * 但都是 `private`，新建服务器的页面只能**照着重写一遍** ——
 * 结果两页的行高、字号、选中色、勾选图标都出现了细微差异。
 *
 * 现在把它们提到这里，两个页面共用同一份实现。
 * 以后再改样式，改一处就够了。
 */

/**
 * 左栏的小节标题（「游戏版本」「加载器」这类）。
 *
 * @param text 文案；调用方用 `stringResource` 传进来（这里不放 Context）
 */
@Composable
fun QkFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 右侧「版本列表」里的一行。
 *
 * @param version 显示的主文字（版本号）
 * @param selected 是否选中
 * @param trailing 可选的右侧小字（「正式」这类渠道标签）；null 就不显示
 * @param onClick 点击
 */
@Composable
fun QkVersionRow(
    version: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = version,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.height(18.dp),
                )
            }
        }
    }
}

/**
 * 右侧列表的标题行：「选择版本」+ 右侧数量。
 *
 * @param trailing 右侧小字（「919 个」）；null 就不显示
 */
@Composable
fun QkListHeader(title: String, trailing: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = QkSpacing.sm, end = 20.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 版本列表里的分组标题（「正式（103）」这种）。
 *
 * 与 `CreateInstancePage` 原来的 `GroupTitle` 同一实现 ——
 * 抬到这里是为了服务器页也能用同一份，避免两页字号/颜色不一样。
 */
@Composable
fun QkGroupTitle(name: String, count: Int) {
    Text(
        text = stringResource(R.string.create_page_group_title, name, count),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QkSpacing.sm, vertical = 6.dp),
    )
}
