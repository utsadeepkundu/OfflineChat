package com.offlinechat.app.di

import android.content.Context
import com.offlinechat.app.bluetooth.BluetoothTransport
import com.offlinechat.app.data.AppDatabase
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.data.ChatDao
import com.offlinechat.app.data.RoomChatStore
import com.offlinechat.app.domain.Transport
import com.offlinechat.app.engine.ChatEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.create(context)

    @Provides
    fun chatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun transport(bluetooth: BluetoothTransport): Transport = bluetooth

    @Provides
    @Singleton
    fun chatEngine(
        @ApplicationContext context: Context,
        transport: Transport,
        store: RoomChatStore,
        preferences: AppPreferences,
        @AppScope scope: CoroutineScope,
    ): ChatEngine {
        // App-specific storage: needs no storage permission and is removed together with the app.
        val received = context.getExternalFilesDir("received") ?: File(context.filesDir, "received")
        return ChatEngine(
            transport = transport,
            store = store,
            identity = preferences,
            settings = preferences,
            scope = scope,
            io = Dispatchers.IO,
            partsDir = File(context.filesDir, "incoming"),
            receivedDir = received,
        )
    }
}
