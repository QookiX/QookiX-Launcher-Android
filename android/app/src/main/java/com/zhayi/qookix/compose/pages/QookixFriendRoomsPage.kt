package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import kotlinx.coroutines.CancellationException

@Composable
internal fun QookixFriendRoomsPage(account: QookixAccount, canJoin: Boolean, onJoin: (String) -> Unit) {
    val user by account.user.collectAsState()
    if (user == null) {
        QookixSignInCard(account, Modifier.padding(QkSpacing.lg))
        return
    }

    val context = LocalContext.current
    var page by remember(user) { mutableIntStateOf(1) }
    var revision by remember(user) { mutableIntStateOf(0) }
    var rooms by remember(user) { mutableStateOf(emptyList<QookixFriendRoom>()) }
    var hasMore by remember(user) { mutableStateOf(false) }
    var loading by remember(user) { mutableStateOf(true) }
    var error by remember(user) { mutableStateOf<String?>(null) }
    LaunchedEffect(user, page, revision) {
        loading = true
        error = null
        try {
            val result = account.friendRooms(page)
            rooms = if (page == 1) result.rooms else (rooms + result.rooms).distinctBy { it.id }
            hasMore = result.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            error = qookixErrorText(context, failed)
        } finally {
            loading = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(QkSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.qookix_friend_rooms),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { page = 1; revision++ }, enabled = !loading) {
                    Text(stringResource(R.string.qookix_account_refresh))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision++ }) { Text(stringResource(R.string.common_retry)) }
            }
            if (!loading && error == null && rooms.isEmpty()) {
                Text(stringResource(R.string.qookix_friend_rooms_empty), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(rooms, key = { it.id }) { room ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(QkSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            room.title.ifBlank { stringResource(R.string.qookix_friend_room_untitled) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val info = listOf(room.owner, room.version).filter { it.isNotBlank() }.joinToString(" · ")
                        if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onJoin(room.code) }, enabled = canJoin) {
                        Text(stringResource(R.string.multiplayer_join_room))
                    }
                }
            }
        }
        if (hasMore && error == null) {
            item {
                TextButton(onClick = { page++ }, enabled = !loading) {
                    Text(stringResource(R.string.qookix_friend_rooms_more))
                }
            }
        }
    }
}
