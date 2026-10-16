package com.p2pchat.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.p2pchat.app.R

/** 本実装ができるまでの仮画面。各画面の Issue で、それぞれの画面に置き換える。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(title: String, message: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Text(text = message, modifier = Modifier.align(Alignment.Center))
        }
    }
}
