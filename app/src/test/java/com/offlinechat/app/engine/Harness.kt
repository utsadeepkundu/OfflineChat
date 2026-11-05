package com.offlinechat.app.engine

import com.offlinechat.app.core.Hashing
import com.offlinechat.app.core.Timings
import com.offlinechat.app.domain.EngineSettings
import com.offlinechat.app.domain.IdentityProvider
import com.offlinechat.app.domain.ImportedFile
import com.offlinechat.app.testutil.InMemoryStore
import com.offlinechat.app.testutil.PipeNetwork
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

val FAST = Timings(
    pingMs = 300, linkTimeoutMs = 2_000, handshakeMs = 1_500, fileAcceptMs = 2_500, fileCompleteMs = 4_000,
    relayStallMs = 1_500, hostRetryDelayMs = 120, hostRetries = 3, joinDecisionMs = 2_500, typingTtlMs = 400, gracefulCloseMs = 50,
)

class TestSettings(
    @Volatile override var quickConnect: Boolean = true,
    @Volatile override var historyCount: Int = 50,
    @Volatile override var autoAcceptJoins: Boolean = true,
) : EngineSettings

class Device(
    val net: PipeNetwork,
    val address: String,
    val id: Long,
    val name: String,
    settings: TestSettings = TestSettings(),
    timings: Timings = FAST,
) {
    val root: File = Files.createTempDirectory("dev-$name").toFile()
    val store = InMemoryStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = ChatEngine(
        transport = net.transport(address),
        store = store,
        identity = object : IdentityProvider {
            override val selfId = id
            override val selfName = name
        },
        settings = settings,
        scope = scope,
        io = Dispatchers.IO,
        partsDir = File(root, "parts"),
        receivedDir = File(root, "received"),
        timings = timings,
    )

    fun start() = apply { runBlocking { engine.start() } }

    fun stop() {
        engine.stop()
        scope.cancel()
        root.deleteRecursively()
    }

    fun makeFile(name: String, size: Int, seed: Int = 1): ImportedFile {
        val f = File(root, "outgoing/$name")
        f.parentFile.mkdirs()
        f.writeBytes(Random(seed).nextBytes(size))
        return ImportedFile(f, name, "application/octet-stream", size.toLong(), Hashing.sha256(f))
    }
}

fun eventually(timeoutMs: Long = 8_000, message: String = "condition", diagnostics: () -> String = { "" }, check: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching { check() }.getOrDefault(false)) return
        Thread.sleep(25)
    }
    throw AssertionError("Timed out waiting for $message ${diagnostics()}")
}
