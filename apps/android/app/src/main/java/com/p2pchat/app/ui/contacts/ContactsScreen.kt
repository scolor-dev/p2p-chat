package com.p2pchat.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.data.contact.Contact
import com.p2pchat.app.ui.theme.P2PChatTheme

/**
 * 画面の入口。ViewModel から状態を受け取り、表示する画面（[ContactsContent]）に渡す。
 * 画面の移動は、呼び出し側（NavGraph）から受け取ったラムダで行う。
 */
@Composable
fun ContactsScreen(
    onContactClick: (deviceId: String) -> Unit,
    onQrClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContactsContent(uiState=uiState, onContactClick=onContactClick, onQrClick=onQrClick, onSettingsClick=onSettingsClick)
}

/** ViewModel を知らない、表示だけの部分。プレビューやテストで単独で使える。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsContent(
    uiState: ContactsUiState,
    onContactClick: (deviceId: String) -> Unit,
    onQrClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.contacts_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onQrClick,
                    ) {
                        Icon(
                            painter = painterResource(
                                R.drawable.baseline_qr_code_2_24,
                            ),
                            contentDescription = stringResource(
                                R.string.qr_title,
                            ),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSettingsClick,
                    ) {
                        Icon(
                            painter = painterResource(
                                R.drawable.baseline_settings_24,
                            ),
                            contentDescription = stringResource(
                                R.string.settings_title,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor =
                        MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor =
                        MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                uiState.errorMessage != null -> Text(
                    text = stringResource(R.string.contacts_load_error),
                    modifier = Modifier.align(Alignment.Center),
                )
                uiState.contacts.isEmpty() -> Text(
                    text = stringResource(R.string.contacts_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn {
                    items(uiState.contacts, key = { it.deviceId }) { contact ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = contact.name,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = contact.latestMessage,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                when {
                                    contact.sendError -> {
                                        Icon(
                                            painter = painterResource(
                                                R.drawable.baseline_error_24,
                                            ),
                                            contentDescription = stringResource(
                                                R.string.message_send_error,
                                            ),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }

                                    contact.isUnread -> {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = CircleShape,
                                                ),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                onContactClick(contact.deviceId)
                            },
                        )

                    }
                }
            }
        }
    }
}

@Preview(
    name = "連絡先あり",
    showBackground = true,
)
@Composable
private fun ContactsContentPreview() {
    P2PChatTheme(
        darkTheme = false,
        dynamicColor = false,
    ) {
        ContactsContent(
            uiState = ContactsUiState(
                contacts = listOf(
                    ContactListItemUiModel(
                        deviceId = "a1b2c3d4",
                        name = "Alice",
                        latestMessage = "送信に失敗したメッセージです",
                        isUnread = true,
                        sendError = true,
                    ),
                    ContactListItemUiModel(
                        deviceId = "0f1e2d3c",
                        name = "Bob",
                        latestMessage = "未確認のメッセージです",
                        isUnread = true,
                        sendError = false,
                    ),
                    ContactListItemUiModel(
                        deviceId = "12345678",
                        name = "Carol",
                        latestMessage = "確認済みのメッセージです",
                        isUnread = false,
                        sendError = false,
                    ),
                ),
                isLoading = false,
            ),
            onContactClick = {},
            onQrClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(
    name = "読み込み中",
    showBackground = true,
)
@Composable
private fun ContactsLoadingPreview() {
    P2PChatTheme(
        darkTheme = false,
        dynamicColor = false,
    ) {
        ContactsContent(
            uiState = ContactsUiState(
                contacts = emptyList(),
                isLoading = true,
            ),
            onContactClick = {},
            onQrClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(
    name = "連絡先なし",
    showBackground = true,
)
@Composable
private fun ContactsEmptyPreview() {
    P2PChatTheme(
        darkTheme = false,
        dynamicColor = false,
    ) {
        ContactsContent(
            uiState = ContactsUiState(
                contacts = emptyList(),
                isLoading = false,
            ),
            onContactClick = {},
            onQrClick = {},
            onSettingsClick = {},
        )
    }
}