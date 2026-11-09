package com.p2pchat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.p2pchat.app.ui.chat.ChatScreen
import com.p2pchat.app.ui.contacts.ContactsScreen
import com.p2pchat.app.ui.qr.QrScreen
import com.p2pchat.app.ui.settings.SettingsScreen

/** 画面どうしのつながり。どの画面からどの画面へ移れるかは、ここだけに書く。 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Contacts) {
        composable<Contacts> {
            ContactsScreen(
                onContactClick = { deviceId -> navController.navigate(Chat(deviceId)) },
                onQrClick = { navController.navigate(Qr) },
                onSettingsClick = { navController.navigate(Settings) },
            )
        }
        composable<Chat> { entry ->
            ChatScreen(
                deviceId = entry.toRoute<Chat>().deviceId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Qr> { QrScreen(onBack = { navController.popBackStack() }) }
        composable<Settings> { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
