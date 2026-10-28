package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 账号面板。
 *
 * 对应旧界面 `AccountChip.vue`(377 行) + `MsLoginDialog.vue`(163 行)。
 *
 * ── 为什么这个页面重要 ──────────────────────────────────────────────────
 * **没有账号就没法启动游戏。** 首页的「启动游戏」按钮会先检查账号列表，
 * 为空时提示「还没有账号」。所以这个面板挡在「能用」与「不能用」之间。
 *
 * ── 两种账号 ────────────────────────────────────────────────────────────
 *
 * | 类型 | 添加方式 | 特点 |
 * |---|---|---|
 * | 离线 | 输入用户名即可 | 无需联网；皮肤要本地应用 |
 * | 正版 | 微软设备码登录 | 能进正版服、能用正版皮肤 |
 *
 * ── 为什么用「设备码」而不是内嵌网页登录 ─────────────────────────────────
 * 设备码流程（device code flow）让用户在**系统浏览器**里完成授权：
 *   - 不需要内嵌 WebView（本项目正在去 WebView 化）
 *   - 系统浏览器有密码管理器、指纹、2FA —— 都比自建表单安全
 *   - 手机与桌面流程一致，用户熟悉
 */
@Composable
fun AccountPanel(
    accounts: List<AccountUi>,
    loading: Boolean,
    msFlow: MsLoginFlow?,
    error: String?,
    currentUuid: String,
    onAddOffline: (String) -> Unit,
    onStartMsLogin: () -> Unit,
    onOpenVerification: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismissError: () -> Unit,
    /**
     * true = 嵌在别处的滚动容器里（「我的」页）。
     *
     * 这时**不能**再用 `fillMaxSize` / `LazyColumn`：外层已经是
     * `verticalScroll`，内部再来一个无界高度的滚动列表会直接崩
     *（"measured with infinity maximum height constraints"）。
     * 改成普通 Column 铺开（账号一般不超过十个，不需要懒加载）。
     */
    embedded: Boolean = false,
) {
    var showAdd by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(AddMode.Offline) }
    var removeTarget by remember { mutableStateOf<AccountUi?>(null) }

    Column(modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        // ── 顶部标题 + 添加入口 ────────────────────────────────────────
        //
        // 嵌在「我的」页里时，窄屏放不下「标题 + 两个按钮」一行 ——
        // 标题会被挤成竖排（真机/模拟器都复现过），所以改成上下两行。
        if (embedded) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(
                    text = stringResource(R.string.account_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                    FilledTonalButton(onClick = { addMode = AddMode.Offline; showAdd = true }) {
                        Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.account_add_offline))
                    }
                    Button(onClick = {
                        addMode = AddMode.Microsoft
                        showAdd = true
                        onStartMsLogin()
                    }) {
                        Icon(Icons.Filled.Wifi, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.account_ms_login))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.account_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { addMode = AddMode.Offline; showAdd = true }) {
                    Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.account_add_offline))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    addMode = AddMode.Microsoft
                    showAdd = true
                    onStartMsLogin()
                }) {
                    Icon(Icons.Filled.Wifi, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.account_ms_login))
                }
            }
        }

        // ── 正版登录进行中的提示条 ─────────────────────────────────────
        msFlow?.let { flow ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.lg, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(QkSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.account_browser_login),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(QkSpacing.xxs))
                        // 设备码用等宽大字显示 —— 用户要照着敲，等宽避免看错
                        Text(
                            text = stringResource(R.string.account_code, flow.userCode),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (flow.verificationUri.isNotBlank()) {
                            Text(
                                text = flow.verificationUri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (flow.verificationUri.isNotBlank()) {
                        TextButton(onClick = { onOpenVerification(flow.verificationUri) }) {
                            Text(stringResource(R.string.account_open))
                        }
                    }
                }
            }
        }

        // ── 列表 ──────────────────────────────────────────────────────
        when {
            loading && accounts.isEmpty() -> Column(modifier = Modifier.padding(QkSpacing.lg)) {
                SkeletonList(rows = 2, rowHeight = 72.dp)
            }

            // 「我的」页的身份卡已经写了「还没有账号」，这里不再重复一块空态
            embedded && accounts.isEmpty() -> Unit

            accounts.isEmpty() -> Box(
                modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (embedded) Modifier.padding(QkSpacing.lg) else Modifier,
                ) {
                    Text(
                        text = stringResource(R.string.account_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = stringResource(R.string.account_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            embedded -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = QkSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            ) {
                accounts.forEach { a ->
                    AccountCard(
                        account = a,
                        isCurrent = a.uuid == currentUuid,
                        onSelect = { onSelect(a.uuid) },
                        onRemove = { removeTarget = a },
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            ) {
                items(accounts, key = { it.uuid }) { a ->
                    AccountCard(
                        account = a,
                        isCurrent = a.uuid == currentUuid,
                        onSelect = { onSelect(a.uuid) },
                        onRemove = { removeTarget = a },
                    )
                }
            }
        }
    }

    // ── 添加账号对话框 ────────────────────────────────────────────────
    if (showAdd) {
        when (addMode) {
            AddMode.Offline -> OfflineAddDialog(
                onDismiss = { showAdd = false },
                onConfirm = { name ->
                    showAdd = false
                    onAddOffline(name)
                },
            )
            AddMode.Microsoft -> MsHintDialog(
                flow = msFlow,
                onDismiss = { showAdd = false },
                onOpen = onOpenVerification,
            )
        }
    }

    // ── 删除确认 ──────────────────────────────────────────────────────
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.account_delete_title, target.name)) },
            text = {
                Text(
                    if (target.online) {
                        stringResource(R.string.account_delete_online_desc)
                    } else {
                        stringResource(R.string.account_delete_offline_desc)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    onRemove(target.uuid)
                }) {
                    Text(
                        stringResource(R.string.account_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }

    // ── 错误提示 ──────────────────────────────────────────────────────
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.account_notice)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.account_ok)) }
            },
        )
    }
}

@Composable
private fun AccountCard(
    account: AccountUi,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像是首字母圆圈 —— 皮肤图要联网拉，列表里用字母更快更省
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = account.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.account_current),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = if (account.online) stringResource(R.string.account_online)
                    else stringResource(R.string.account_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.account_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OfflineAddDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        // ⚠️ 已经输入用户名就别让点外部关掉（2026-10-17 修，同类第 4 处）
        onDismissRequest = { if (name.isBlank()) onDismiss() },
        title = { Text(stringResource(R.string.account_add_offline_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.account_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = stringResource(R.string.account_offline_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                // 长度限制与 Minecraft 一致（3-16），提前拦掉非法输入
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().length in 3..16,
            ) { Text(stringResource(R.string.account_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_cancel)) }
        },
    )
}

@Composable
private fun MsHintDialog(
    flow: MsLoginFlow?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_ms_login)) },
        text = {
            Column {
                if (flow == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.account_fetching_code),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.account_step_open_url),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = flow.verificationUri.ifBlank { "microsoft.com/link" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(QkSpacing.sm))
                    Text(
                        text = stringResource(R.string.account_step_enter_code),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = flow.userCode,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 3.sp,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(QkSpacing.sm))
                    Text(
                        text = stringResource(R.string.account_done_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            if (flow != null && flow.verificationUri.isNotBlank()) {
                TextButton(onClick = { onOpen(flow.verificationUri) }) {
                    Text(stringResource(R.string.account_open_browser))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_close)) }
        },
    )
}

/** 添加账号的方式。 */
private enum class AddMode { Offline, Microsoft }

/** 账号条目（UI 模型）。 */
data class AccountUi(
    val uuid: String,
    val name: String,
    /** 是否正版账号（微软登录）。 */
    val online: Boolean,
)

/**
 * 正版登录的设备码流程状态。
 *
 * 有值表示「流程进行中」，界面会显示代码与网址。
 */
data class MsLoginFlow(
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int = 0,
)


