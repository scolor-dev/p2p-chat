package com.p2pchat.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
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
    ContactsContent(
        uiState = uiState,
        onContactClick = onContactClick,
        onQrClick = onQrClick,
        onSettingsClick = onSettingsClick,
    )
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
    var selectedContactForDetails by remember { mutableStateOf<ContactListItemUiModel?>(null) }

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
                    IconButton(onClick = onQrClick) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_qr_code_2_24),
                            contentDescription = stringResource(R.string.qr_title),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_settings_24),
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
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
                        ContactListItem(
                            contact = contact,
                            onClick = { onContactClick(contact.deviceId) },
                            onInfoClick = { selectedContactForDetails = contact },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selectedContactForDetails?.let { contact ->
        ContactDetailDialog(
            contact = contact,
            onDismiss = { selectedContactForDetails = null },
        )
    }
}

@Composable
private fun ContactListItem(
    contact: ContactListItemUiModel,
    onClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = contact.name,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VerificationBadge(isVerified = contact.isVerified)
            }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    contact.sendError -> {
                        Icon(
                            painter = painterResource(R.drawable.baseline_error_24),
                            contentDescription = stringResource(R.string.message_send_error),
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

                TextButton(onClick = onInfoClick) {
                    Text("詳細")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun VerificationBadge(isVerified: Boolean) {
    val text = if (isVerified) {
        stringResource(R.string.contacts_verified)
    } else {
        stringResource(R.string.contacts_unverified)
    }
    val colors = if (isVerified) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = colors,
        enabled = false,
    )
}

@Composable
private fun ContactDetailDialog(
    contact: ContactListItemUiModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_details_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.contact_details_device_id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = contact.deviceId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )

                Text(
                    text = stringResource(R.string.contact_details_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                VerificationBadge(isVerified = contact.isVerified)

                Text(
                    text = stringResource(R.string.contact_details_fingerprint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatFingerprint(contact.publicKey),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

private fun formatFingerprint(publicKey: String): String {
    if (publicKey.isBlank()) return "（未登録）"
    return publicKey.chunked(4).take(8).joinToString(":")
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
                        publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
                        isVerified = true,
                        latestMessage = "送信に失敗したメッセージです",
                        isUnread = true,
                        sendError = true,
                    ),
                    ContactListItemUiModel(
                        deviceId = "0f1e2d3c",
                        name = "Bob",
                        publicKey = "",
                        isVerified = false,
                        latestMessage = "未確認のメッセージです",
                        isUnread = true,
                        sendError = false,
                    ),
                    ContactListItemUiModel(
                        deviceId = "12345678",
                        name = "Carol",
                        publicKey = "",
                        isVerified = false,
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
