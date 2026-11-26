package com.zhayi.qookix.compose.pages

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkTabRow
import com.zhayi.qookix.compose.ui.QkTabContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class ProfileRefresh { Checking, Current, Unavailable }

@Composable
internal fun QookixAccountSettings(account: QookixAccount, favorites: ContentFavorites) {
    val user by account.user.collectAsState()
    val profile = user
    if (profile == null) {
        QookixSignInCard(account, Modifier.padding(QkSpacing.lg))
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember(account, context, scope) { QookixActions(context, scope) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    var profileRefresh by remember(account, profile.username) { mutableStateOf(ProfileRefresh.Checking) }
    fun refresh() = actions.run {
        profileRefresh = ProfileRefresh.Checking
        try {
            account.refreshProfile()
            profileRefresh = ProfileRefresh.Current
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            profileRefresh = ProfileRefresh.Unavailable
            throw failure
        }
    }
    LaunchedEffect(account, profile.username) {
        refresh()
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(QkSpacing.md)) {
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wide = maxWidth >= 700.dp || (landscape && maxWidth >= 450.dp)
        val identity: @Composable () -> Unit = {
            QookixIdentity(
                profile,
                busy = actions.busy,
                onRefresh = ::refresh,
                onLogout = { confirmLogout = true },
            )
        }
        val settings: @Composable () -> Unit = {
            Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    val labels = listOf(
                        stringResource(R.string.qookix_tab_profile), stringResource(R.string.qookix_tab_security),
                        stringResource(R.string.qookix_tab_sync),
                    )
                    val selectedTab = tab.coerceIn(0, labels.lastIndex)
                    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = selectedTab) { labels.size }
                    val selectTab: (Int) -> Unit = { actions.message = null; tab = it }
                    QkTabRow(
                        labels = labels,
                        selectedIndex = selectedTab,
                        onSelect = selectTab,
                        minTabWidth = 64,
                        pagerState = pager,
                    )
                    if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    QkTabContent(labels.indices.toList(), selectedTab, selectTab, Modifier.weight(1f), pagerState = pager) { page ->
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QkSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                        ) {
                            actions.message?.let {
                                Text(it, color = if (actions.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                            when (page) {
                                0 -> QookixProfilePane(account, profile, profileRefresh, actions)
                                1 -> QookixSecurityPane(account, actions)
                                2 -> QookixSyncPane(account, favorites, actions)
                            }
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(QkSpacing.md)) {
                Box(Modifier.weight(0.31f)) { identity() }
                Box(Modifier.weight(0.69f)) { settings() }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                identity()
                Box(Modifier.weight(1f)) { settings() }
            }
        }
    }
    if (confirmLogout) {
        QookixConfirm(R.string.qookix_account_logout, R.string.qookix_account_logout_confirm, { confirmLogout = false }) {
            confirmLogout = false
            actions.run { account.logout() }
        }
    }
}

@Composable
private fun QookixIdentity(profile: QookixUser, busy: Boolean, onRefresh: () -> Unit, onLogout: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(QkSpacing.md), verticalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                Surface(Modifier.size(40.dp), CircleShape, MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.nickname.isNotBlank() && profile.nickname != profile.username) {
                        Text("@${profile.username}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    } else {
                        Text(stringResource(R.string.qookix_signed_in), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.qookix_account_refresh))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = onLogout, enabled = !busy) {
                Text(stringResource(R.string.qookix_account_logout), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QookixProfilePane(account: QookixAccount, profile: QookixUser, refresh: ProfileRefresh, actions: QookixActions) {
    var editNickname by remember { mutableStateOf(false) }
    var editEmail by remember { mutableStateOf(false) }
    val audit = when (profile.nicknameStatus) {
        "pending" -> stringResource(R.string.qookix_review_pending)
        "rejected" -> stringResource(R.string.qookix_review_rejected)
        else -> null
    }
    QookixSettingRow(R.string.qookix_nickname, listOfNotNull(profile.displayName, audit).joinToString(" · ")) {
        TextButton(onClick = { editNickname = true }, enabled = !actions.busy) { Text(stringResource(R.string.qookix_edit)) }
    }
    val emailStatus = if (profile.emailVerified) R.string.qookix_verified else R.string.qookix_unverified
    QookixSettingRow(
        R.string.qookix_account_email,
        listOf(profile.email, stringResource(emailStatus)).filter { it.isNotBlank() }.joinToString(" · "),
    ) {
        TextButton(onClick = { editEmail = true }, enabled = !actions.busy) { Text(stringResource(R.string.qookix_email_change)) }
    }
    QookixSettingRow(
        R.string.qookix_minecraft_binding,
        qookixMinecraftBinding(profile, refresh),
    )
    if (editNickname) {
        QookixFormDialog(
            R.string.qookix_nickname,
            listOf(QookixField(R.string.qookix_nickname, initial = profile.nickname)),
            actions, onDismiss = { editNickname = false },
            valid = { it[0].trim().run { codePointCount(0, length) in 1..24 } },
        ) { values ->
            account.changeNickname(values[0])
            editNickname = false
        }
    }
    if (editEmail) {
        QookixFormDialog(
            R.string.qookix_email_change,
            listOf(QookixField(R.string.qookix_new_email, email = true)),
            actions, onDismiss = { editEmail = false },
            hint = R.string.qookix_email_change_hint, success = R.string.qookix_email_sent,
            valid = { android.util.Patterns.EMAIL_ADDRESS.matcher(it[0].trim()).matches() },
        ) { values ->
            account.changeEmail(values[0])
            editEmail = false
        }
    }
}

@Composable
private fun qookixMinecraftBinding(profile: QookixUser, refresh: ProfileRefresh): String {
    val name = profile.mcName.orEmpty().trim()
    val uuid = profile.mcUuid.orEmpty().trim()
    val status = when {
        refresh == ProfileRefresh.Checking -> return stringResource(R.string.qookix_mc_checking)
        refresh == ProfileRefresh.Unavailable && profile.mcVerified == true -> R.string.qookix_mc_cached_verified
        refresh == ProfileRefresh.Unavailable -> return stringResource(R.string.qookix_mc_unknown)
        profile.mcVerified == true -> R.string.qookix_verified
        profile.mcVerified == false && (name.isNotEmpty() || uuid.isNotEmpty()) -> R.string.qookix_unverified
        profile.mcVerified == false && profile.mcName != null && profile.mcUuid != null -> R.string.qookix_mc_unbound
        else -> R.string.qookix_mc_unknown
    }
    return listOf(name, stringResource(status)).filter(String::isNotEmpty).joinToString(" · ")
}

@Composable
private fun QookixSyncPane(account: QookixAccount, favorites: ContentFavorites, actions: QookixActions) {
    val context = LocalContext.current
    var confirmSync by remember { mutableStateOf(false) }
    var cloudCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(account) {
        actions.awaitIdle()
        actions.run { cloudCount = account.cloudFavorites().length() }
    }
    Text(stringResource(R.string.qookix_favorite_sync_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.md)) {
        Text(stringResource(R.string.qookix_favorite_local_count, favorites.items.size), style = MaterialTheme.typography.titleSmall)
        if (cloudCount >= 0) {
            Text(stringResource(R.string.qookix_favorite_cloud_count, cloudCount), style = MaterialTheme.typography.titleSmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
        Button(onClick = { confirmSync = true }, enabled = !actions.busy) { Text(stringResource(R.string.qookix_favorite_sync)) }
        OutlinedButton(onClick = {
            actions.run {
                val cloud = account.cloudFavorites()
                cloudCount = cloud.length()
                val count = favorites.mergeFromCloud(cloud)
                actions.message = context.getString(R.string.qookix_favorite_imported, count)
            }
        }, enabled = !actions.busy) { Text(stringResource(R.string.qookix_favorite_import)) }
    }
    if (confirmSync) {
        QookixConfirm(R.string.qookix_favorite_sync, R.string.qookix_favorite_sync_confirm, { confirmSync = false }) {
            confirmSync = false
            actions.run {
                val merged = account.syncFavorites(favorites.cloudSnapshot())
                cloudCount = merged.length()
                val count = favorites.mergeFromCloud(merged)
                actions.message = context.getString(R.string.qookix_favorite_synced, count)
            }
        }
    }
}

@Composable
internal fun QookixSettingRow(@StringRes title: Int, description: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = QkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun QookixConfirm(@StringRes title: Int, @StringRes body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

internal class QookixActions(private val context: Context, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
        private set

    suspend fun awaitIdle() {
        snapshotFlow { busy }.first { !it }
    }

    fun run(@StringRes success: Int? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        failed = false
        scope.launch {
            try {
                action()
                if (success != null) message = context.getString(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                message = qookixErrorText(context, error)
            } finally {
                busy = false
            }
        }
    }
}
