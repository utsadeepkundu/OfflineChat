package com.offlinechat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.offlinechat.app.domain.EngineSettings
import com.offlinechat.app.domain.IdentityProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val displayName: String,
    val quickConnect: Boolean = true,
    val autoAcceptJoins: Boolean = false,
    val historyCount: Int = 50,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val notifications: Boolean = true,
    val compressImages: Boolean = true,
    val onboardingDone: Boolean = false,
)

/**
 * Identity and settings, kept in SharedPreferences: they are tiny, read synchronously by the engine,
 * and observed by the UI through [settings].
 */
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) : IdentityProvider, EngineSettings {

    private val prefs: SharedPreferences = context.getSharedPreferences("offlinechat", Context.MODE_PRIVATE)

    override val selfId: Long = prefs.getLong(KEY_ID, 0L).takeIf { it != 0L } ?: run {
        var id: Long
        do {
            id = Random.nextLong()
        } while (id == 0L)
        prefs.edit().putLong(KEY_ID, id).apply()
        id
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    override val selfName: String get() = _settings.value.displayName
    override val quickConnect: Boolean get() = _settings.value.quickConnect
    override val historyCount: Int get() = _settings.value.historyCount
    override val autoAcceptJoins: Boolean get() = _settings.value.autoAcceptJoins

    fun update(transform: (UserSettings) -> UserSettings) {
        val next = transform(_settings.value).let { it.copy(displayName = cleanName(it.displayName)) }
        _settings.value = next
        prefs.edit()
            .putString(KEY_NAME, next.displayName)
            .putBoolean(KEY_QUICK, next.quickConnect)
            .putBoolean(KEY_AUTO_JOIN, next.autoAcceptJoins)
            .putInt(KEY_HISTORY, next.historyCount)
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putBoolean(KEY_HAPTICS, next.haptics)
            .putBoolean(KEY_NOTIFY, next.notifications)
            .putBoolean(KEY_COMPRESS, next.compressImages)
            .putBoolean(KEY_ONBOARDED, next.onboardingDone)
            .apply()
    }

    private fun load() = UserSettings(
        displayName = cleanName(prefs.getString(KEY_NAME, null) ?: defaultName()),
        quickConnect = prefs.getBoolean(KEY_QUICK, true),
        autoAcceptJoins = prefs.getBoolean(KEY_AUTO_JOIN, false),
        historyCount = prefs.getInt(KEY_HISTORY, 50).coerceIn(0, 200),
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        notifications = prefs.getBoolean(KEY_NOTIFY, true),
        compressImages = prefs.getBoolean(KEY_COMPRESS, true),
        onboardingDone = prefs.getBoolean(KEY_ONBOARDED, false),
    )

    private fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(24).ifBlank { defaultName() }

    private fun defaultName(): String = (Build.MODEL ?: "").trim().ifBlank { "Me" }.take(24)

    private companion object {
        const val KEY_ID = "self_id"
        const val KEY_NAME = "name"
        const val KEY_QUICK = "quick_connect"
        const val KEY_AUTO_JOIN = "auto_join"
        const val KEY_HISTORY = "history"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic"
        const val KEY_HAPTICS = "haptics"
        const val KEY_NOTIFY = "notify"
        const val KEY_COMPRESS = "compress"
        const val KEY_ONBOARDED = "onboarded"
    }
}
