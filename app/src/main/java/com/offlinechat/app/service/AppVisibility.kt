package com.offlinechat.app.service

/** Whether the UI is on screen. Notifications are only posted while it is not. */
object AppVisibility {
    @Volatile
    var inForeground: Boolean = false
}
