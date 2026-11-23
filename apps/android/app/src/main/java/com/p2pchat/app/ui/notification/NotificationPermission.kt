package com.p2pchat.app.ui.notification

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.p2pchat.app.P2PChatApp
import kotlinx.coroutines.flow.first

/** POST_NOTIFICATIONS（Android 13 以降）が許可済みか。それ未満は不要なので true。 */
fun isNotificationPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** 通知の権限を要求する関数を返す。許可済み・Android 12 以下なら何もしない。拒否されても何も起きない。 */
@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒否されても動き続ける */ }
    return remember(launcher, context) {
        {
            if (!isNotificationPermissionGranted(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** 初めてチャット画面を開いたときに、一度だけ通知の権限を要求する。 */
@Composable
fun RequestNotificationPermissionOnFirstChat() {
    val context = LocalContext.current
    val request = rememberNotificationPermissionRequester()
    LaunchedEffect(Unit) {
        if (isNotificationPermissionGranted(context)) return@LaunchedEffect
        val preferences = (context.applicationContext as? P2PChatApp)?.container?.appPreferencesRepository ?: return@LaunchedEffect
        if (preferences.notificationPermissionRequested.first()) return@LaunchedEffect
        preferences.markNotificationPermissionRequested()
        request()
    }
}
