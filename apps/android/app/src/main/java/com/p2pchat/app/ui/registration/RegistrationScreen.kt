package com.p2pchat.app.ui.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.ui.theme.P2PChatTheme

/**
 * 登録画面の入口。初回起動で、端末の名前を入力して登録する。
 * 画面の移動は、呼び出し側（NavGraph）から受け取ったラムダで行う。
 */
@Composable
fun RegistrationScreen(
    onOpenSettings: () -> Unit,
    onRegistered: () -> Unit,
    viewModel: RegistrationViewModel = viewModel(factory = RegistrationViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegistrationContent(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onRegister = viewModel::register,
        onOpenSettings = onOpenSettings,
        onContinue = onRegistered,
    )
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationContent(
    uiState: RegistrationUiState,
    onNameChange: (String) -> Unit,
    onRegister: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.registration_title)) },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_title)) }
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
            if (uiState.registeredDeviceId != null) {
                RegisteredBody(uiState.registeredDeviceId, onContinue)
            } else {
                FormBody(uiState, onNameChange, onRegister, onOpenSettings)
            }
        }
    }
}

@Composable
private fun RegisteredBody(deviceId: String, onContinue: () -> Unit) {
    Text(stringResource(R.string.registration_done), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.registration_device_id_label), style = MaterialTheme.typography.labelMedium)
    SelectionContainer { Text(deviceId, style = MaterialTheme.typography.bodyLarge) }
    Button(onClick = onContinue) { Text(stringResource(R.string.registration_continue)) }
}

@Composable
private fun FormBody(
    uiState: RegistrationUiState,
    onNameChange: (String) -> Unit,
    onRegister: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Text(stringResource(R.string.registration_description), style = MaterialTheme.typography.bodyMedium)

    if (!uiState.isServerConfigured) {
        Text(
            text = stringResource(R.string.registration_server_not_configured),
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.registration_open_settings))
        }
    }

    OutlinedTextField(
        value = uiState.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.registration_name_label)) },
        supportingText = {
            Text(
                stringResource(
                    when (uiState.nameError) {
                        NameError.Empty -> R.string.registration_name_error_empty
                        NameError.TooLong -> R.string.registration_name_error_too_long
                        null -> R.string.registration_name_hint
                    },
                ),
            )
        },
        isError = uiState.nameError != null,
        enabled = !uiState.isRegistering,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = onRegister,
        enabled = uiState.isServerConfigured && !uiState.isRegistering,
    ) {
        if (uiState.isRegistering) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.registration_register))
        }
    }

    uiState.error?.let { error ->
        Text(text = errorText(error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun errorText(error: RegistrationError): String = when (error) {
    RegistrationError.ServerNotConfigured -> stringResource(R.string.registration_server_not_configured)
    RegistrationError.KeyFailed -> stringResource(R.string.registration_error_key)
    RegistrationError.Network -> stringResource(R.string.registration_error_network)
    is RegistrationError.Rejected -> stringResource(R.string.registration_error_rejected, error.detail)
    RegistrationError.StorageFailed -> stringResource(R.string.registration_error_storage)
}

@Preview(showBackground = true)
@Composable
private fun RegistrationContentPreview() {
    P2PChatTheme {
        RegistrationContent(
            uiState = RegistrationUiState(isLoading = false, name = "Pixel 8", isServerConfigured = true),
            onNameChange = {},
            onRegister = {},
            onOpenSettings = {},
            onContinue = {},
        )
    }
}
