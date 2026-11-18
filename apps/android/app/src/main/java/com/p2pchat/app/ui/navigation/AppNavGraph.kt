package com.p2pchat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.p2pchat.app.ui.chat.ChatScreen
import com.p2pchat.app.ui.contacts.ContactsScreen
import com.p2pchat.app.ui.qr.QrScreen
import com.p2pchat.app.ui.registration.RegistrationScreen
import com.p2pchat.app.ui.settings.SettingsScreen

/** 画面どうしのつながり。どの画面からどの画面へ移れるかは、ここだけに書く。 */
@Composable
fun AppNavGraph(startViewModel: StartViewModel = viewModel(factory = StartViewModel.Factory)) {
    val navController = rememberNavController()

    // 登録済みかどうかが分かるまでは、何も表示しない（登録画面が一瞬見えるのを防ぐ）
    val start by startViewModel.start.collectAsStateWithLifecycle()
    val startDestination: Any = when (start) {
        StartDestination.Loading -> return
        StartDestination.Registration -> Registration
        StartDestination.Contacts -> Contacts
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Registration> {
            RegistrationScreen(
                onOpenSettings = { navController.navigate(Settings) },
                onRegistered = {
                    // 登録画面には戻れないようにして、連絡先一覧へ
                    navController.navigate(Contacts) { popUpTo<Registration> { inclusive = true } }
                },
            )
        }
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
