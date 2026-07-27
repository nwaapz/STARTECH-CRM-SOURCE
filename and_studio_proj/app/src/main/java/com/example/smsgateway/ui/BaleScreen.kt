package com.example.smsgateway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smsgateway.R
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.BaleApi
import com.example.smsgateway.BaleChatItem
import com.example.smsgateway.BaleChatStore
import com.example.smsgateway.BalePoller
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BaleScreen(
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { BaleApi.getInstance() }
    val poller = remember { BalePoller.getInstance(context) }
    val chatStore = remember { BaleChatStore.getInstance(context) }
    val connectErrorFallback = stringResource(R.string.bale_connect_error)

    var tokenInput by remember { mutableStateOf(settingsStore.getBaleToken()) }
    var isActive by remember { mutableStateOf(settingsStore.isBaleBotActive()) }
    var botTitle by remember { mutableStateOf(settingsStore.getBaleBotInfo()?.title.orEmpty()) }
    var botUsername by remember { mutableStateOf(settingsStore.getBaleBotInfo()?.username.orEmpty()) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var chatItems by remember { mutableStateOf(chatStore.getMessages()) }
    val listState = rememberLazyListState()

    DisposableEffect(chatStore) {
        val listener = { chatItems = chatStore.getMessages() }
        chatStore.addListener(listener)
        onDispose { chatStore.removeListener(listener) }
    }

    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.bale_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.bale_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        connectError = null
                    },
                    label = { Text(stringResource(R.string.bale_token_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isConnecting,
                )

                Button(
                    onClick = {
                        scope.launch {
                            isConnecting = true
                            connectError = null
                            try {
                                val info = withContext(Dispatchers.IO) {
                                    api.getMe(tokenInput.trim())
                                }
                                settingsStore.setBaleBotActive(info, tokenInput.trim())
                                isActive = true
                                botTitle = info.title
                                botUsername = info.username
                                poller.start()
                            } catch (e: Exception) {
                                connectError = e.message ?: connectErrorFallback
                                isActive = false
                            } finally {
                                isConnecting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = tokenInput.isNotBlank() && !isConnecting,
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.bale_connect))
                    }
                }

                BaleStatusRow(
                    isActive = isActive,
                    botTitle = botTitle,
                    botUsername = botUsername,
                    error = connectError,
                )

                if (isActive) {
                    TextButton(
                        onClick = {
                            poller.stop()
                            settingsStore.clearBaleBot()
                            isActive = false
                            botTitle = ""
                            botUsername = ""
                            tokenInput = ""
                            connectError = null
                        },
                    ) {
                        Text(stringResource(R.string.bale_disconnect))
                    }
                }
            }
        }

        if (isActive || chatItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bale_chats_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50)),
                        )
                        Text(
                            text = stringResource(R.string.bale_polling),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (chatItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.bale_no_messages),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chatItems, key = { it.messageId }) { item ->
                        BaleChatCard(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun BaleStatusRow(
    isActive: Boolean,
    botTitle: String,
    botUsername: String,
    error: String?,
) {
    when {
        error != null -> {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        isActive -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                )
                val label = buildString {
                    append(stringResource(R.string.bale_connected))
                    if (botTitle.isNotBlank()) {
                        append(" — ")
                        append(botTitle)
                    }
                    if (botUsername.isNotBlank()) {
                        append(" (@")
                        append(botUsername)
                        append(')')
                    }
                }
                Text(
                    text = label,
                    color = Color(0xFF2E7D32),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Text(
                    text = stringResource(R.string.bale_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BaleChatCard(item: BaleChatItem) {
    val time = remember(item.timestamp) {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale("fa", "IR")).format(Date(item.timestamp))
    }
    val chatLabel = remember(item.chatId) {
        if (item.chatId.length <= 12) item.chatId else "${item.chatId.take(6)}…${item.chatId.takeLast(4)}"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = chatLabel,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(time, style = MaterialTheme.typography.bodySmall)
            Text(
                text = item.text.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
            item.senderName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.phoneNumber?.let { phone ->
                Text(
                    text = stringResource(R.string.bale_sender_phone, phone),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
