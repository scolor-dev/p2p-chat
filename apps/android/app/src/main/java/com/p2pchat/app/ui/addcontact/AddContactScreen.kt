package com.p2pchat.app.ui.addcontact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2pchat.app.R
import com.p2pchat.app.data.network.RemoteDevice
import com.p2pchat.app.ui.theme.P2PChatTheme

/**
 * ID で連絡先を追加する画面の入口。追加できたら [onAdded] で前の画面へ戻る。
 * 画面の移動は、呼び出し側（NavGraph）から受け取ったラムダで行う。
 */
@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AddContactViewModel = viewModel(factory = AddContactViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.isAdded) {
        if (uiState.isAdded) onAdded()
    }
    AddContactContent(
        uiState = uiState,
        onDeviceIdChange = viewModel::onDeviceIdChange,
        onSearch = viewModel::search,
        onAdd = viewModel::add,
        onBack = onBack,
    )
}

/** ViewModel を知らない、表示だけの部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactContent(
    uiState: AddContactUiState,
    onDeviceIdChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.add_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_arrow_back_ios_new_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_contact_description), style = MaterialTheme.typography.bodyMedium)

            val busy = uiState.isSearching || uiState.isAdding
            OutlinedTextField(
                value = uiState.deviceId,
                onValueChange = onDeviceIdChange,
                label = { Text(stringResource(R.string.add_contact_id_label)) },
                isError = uiState.error != null,
                supportingText = uiState.error?.let { error -> { Text(errorText(error)) } },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onSearch, enabled = !busy && uiState.found == null) {
                if (uiState.isSearching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.add_contact_search))
                }
            }

            uiState.found?.let { found ->
                Text(stringResource(R.string.add_contact_found_label), style = MaterialTheme.typography.labelMedium)
                Text(found.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.add_contact_unverified_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onAdd, enabled = !busy) {
                    if (uiState.isAdding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.add_contact_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun errorText(error: AddContactError): String = stringResource(
    when (error) {
        AddContactError.Empty -> R.string.add_contact_error_empty
        AddContactError.ServerNotConfigured -> R.string.add_contact_error_server_not_configured
        AddContactError.NotFound -> R.string.add_contact_error_not_found
        AddContactError.Self -> R.string.add_contact_error_self
        AddContactError.AlreadyAdded -> R.string.add_contact_error_already_added
        AddContactError.Network -> R.string.add_contact_error_network
        AddContactError.StorageFailed -> R.string.add_contact_error_storage
    },
)

@Preview(showBackground = true)
@Composable
private fun AddContactContentPreview() {
    P2PChatTheme {
        AddContactContent(
            uiState = AddContactUiState(
                deviceId = "a1b2c3d4e5f60718",
                found = RemoteDevice("a1b2c3d4e5f60718", "Alice", "MFkw"),
            ),
            onDeviceIdChange = {},
            onSearch = {},
            onAdd = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AddContactErrorPreview() {
    P2PChatTheme {
        AddContactContent(
            uiState = AddContactUiState(deviceId = "zzzz", error = AddContactError.NotFound),
            onDeviceIdChange = {},
            onSearch = {},
            onAdd = {},
            onBack = {},
        )
    }
}
