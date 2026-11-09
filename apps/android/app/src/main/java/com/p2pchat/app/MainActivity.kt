package com.p2pchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.p2pchat.app.ui.navigation.AppNavGraph
import com.p2pchat.app.ui.theme.P2PChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            P2PChatTheme {
                AppNavGraph()
            }
        }
    }
}
