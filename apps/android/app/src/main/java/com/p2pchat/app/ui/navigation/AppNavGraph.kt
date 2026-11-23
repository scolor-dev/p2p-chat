package com.p2pchat.app.ui.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.navigation.navDeepLink
import com.p2pchat.app.notification.ChatDeepLink
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.p2pchat.app.ui.addcontact.AddContactScreen
import com.p2pchat.app.ui.chat.ChatScreen
import com.p2pchat.app.ui.contacts.ContactsScreen
import com.p2pchat.app.ui.qr.QrScanScreen
import com.p2pchat.app.ui.qr.QrScreen
import com.p2pchat.app.ui.registration.RegistrationScreen
import com.p2pchat.app.ui.settings.SettingsScreen

/** 画面どうしのつながり。どの画面からどの画面へ移れるかは、ここだけに書く。 */
@Composable
fun AppNavGraph(startViewModel: StartViewModel = viewModel(factory = StartViewModel.Factory)) {
    val navController = rememberNavController()

    // 通知のタップ（既に開いている Activity への新しい Intent）で、その相手のチャットを開く
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { intent -> navController.handleDeepLink(intent) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

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
                onAddClick = { navController.navigate(AddContact) },
                onSettingsClick = { navController.navigate(Settings) },
            )
        }
        composable<Chat>(deepLinks = listOf(navDeepLink<Chat>(basePath = ChatDeepLink.BASE))) { entry ->
            ChatScreen(
                deviceId = entry.toRoute<Chat>().deviceId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AddContact> {
            AddContactScreen(
                onBack = { navController.popBackStack() },
                onAdded = { navController.popBackStack() },
            )
        }
        composable<Qr> {
            QrScreen(
                onBack = { navController.popBackStack() },
                onScanClick = { navController.navigate(QrScan) },
            )
        }
        composable<QrScan> { QrScanScreen(onBack = { navController.popBackStack() }) }
        composable<Settings> { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
