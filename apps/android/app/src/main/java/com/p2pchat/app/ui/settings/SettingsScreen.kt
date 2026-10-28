package com.p2pchat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.ui.theme.P2PChatTheme

/** 設定画面の入口。接続先のサーバー（IP アドレスとポート）を入力・保存し、接続を試せる。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        uiState = uiState,
        onBack = onBack,
        onHostChange = viewModel::onHostChange,
        onPortChange = viewModel::onPortChange,
        onSave = viewModel::save,
        onTestConnection = viewModel::testConnection,
    )
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_server_section), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.settings_host_label)) },
                placeholder = { Text("192.168.1.10") },
                supportingText = {
                    Text(
                        stringResource(
                            if (uiState.hostError) R.string.settings_host_error else R.string.settings_host_hint,
                        ),
                    )
                },
                isError = uiState.hostError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.settings_port_label)) },
                supportingText = if (uiState.portError) {
                    { Text(stringResource(R.string.settings_port_error)) }
                } else {
                    null
                },
                isError = uiState.portError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave, enabled = !uiState.isSaved) {
                    Text(stringResource(R.string.settings_save))
                }
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = uiState.connectionTest != ConnectionTestState.Running,
                ) {
                    Text(stringResource(R.string.settings_test_connection))
                }
            }
            if (uiState.isSaved) {
                Text(stringResource(R.string.settings_saved), style = MaterialTheme.typography.bodySmall)
            }
            ConnectionTestResult(uiState.connectionTest)
        }
    }
}

@Composable
private fun ConnectionTestResult(state: ConnectionTestState) {
    when (state) {
        ConnectionTestState.Idle -> Unit
        ConnectionTestState.Running -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_test_running))
        }
        ConnectionTestState.Success -> Text(
            text = stringResource(R.string.settings_test_success),
            color = MaterialTheme.colorScheme.primary,
        )
        is ConnectionTestState.Failure -> Text(
            text = stringResource(R.string.settings_test_failure, state.detail),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    P2PChatTheme {
        SettingsContent(
            uiState = SettingsUiState(
                isLoading = false,
                host = "192.168.1.10",
                port = "8080",
                isSaved = true,
                connectionTest = ConnectionTestState.Success,
            ),
            onBack = {},
            onHostChange = {},
            onPortChange = {},
            onSave = {},
            onTestConnection = {},
        )
    }
}
