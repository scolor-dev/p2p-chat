package com.p2pchat.app.data.settings

import kotlinx.coroutines.flow.MutableStateFlow

class FakeAppPreferencesRepository(
    backgroundCommsEnabled: Boolean = true,
    notificationPermissionRequested: Boolean = false,
) : AppPreferencesRepository {
    override val backgroundCommsEnabled = MutableStateFlow(backgroundCommsEnabled)
    override suspend fun setBackgroundCommsEnabled(enabled: Boolean) {
        this.backgroundCommsEnabled.value = enabled
    }

    override val notificationPermissionRequested = MutableStateFlow(notificationPermissionRequested)
    override suspend fun markNotificationPermissionRequested() {
        notificationPermissionRequested.value = true
    }
}
