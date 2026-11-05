package com.offlinechat.app

import android.app.Application
import com.offlinechat.app.bluetooth.BluetoothController
import com.offlinechat.app.service.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OfflineChatApp : Application() {
    @Inject lateinit var bluetooth: BluetoothController
    @Inject lateinit var notifications: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        bluetooth.register()
    }
}
