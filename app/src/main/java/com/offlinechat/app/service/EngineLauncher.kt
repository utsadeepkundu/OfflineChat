package com.offlinechat.app.service

import com.offlinechat.app.di.AppScope
import com.offlinechat.app.engine.ChatEngine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Starts and stops the engine exactly once per service lifetime. */
@Singleton
class EngineLauncher @Inject constructor(
    private val engine: ChatEngine,
    @AppScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) scope.launch(Dispatchers.IO) { engine.start() }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) engine.stop()
    }
}
