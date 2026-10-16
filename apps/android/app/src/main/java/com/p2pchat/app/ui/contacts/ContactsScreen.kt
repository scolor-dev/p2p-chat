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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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
    ContactsContent(uiState, onContactClick, onQrClick, onSettingsClick)
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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = {
                    TextButton(onClick = onQrClick) { Text(stringResource(R.string.qr_title)) }
                },
                actions = {
                    TextButton(onClick = onSettingsClick) { Text(stringResource(R.string.settings_title)) }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.contacts.isEmpty() -> Text(
                    text = stringResource(R.string.contacts_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn {
                    items(uiState.contacts, key = { it.deviceId }) { contact ->
                        ListItem(
                            headlineContent = { Text(contact.name) },
                            supportingContent = { Text(contact.deviceId) },
                            modifier = Modifier.clickable { onContactClick(contact.deviceId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactsContentPreview() {
    P2PChatTheme {
        ContactsContent(
            uiState = ContactsUiState(
                contacts = listOf(Contact("a1b2c3d4e5f60718", "Alice"), Contact("0f1e2d3c4b5a6978", "Bob")),
                isLoading = false,
            ),
            onContactClick = {},
            onQrClick = {},
            onSettingsClick = {},
        )
    }
}
