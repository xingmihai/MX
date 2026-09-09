package moe.fuqiuluo.mamu.script

import org.luaj.vm2.LuaError
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ScriptHost(
    private val poster: (Runnable) -> Unit = { it.run() },
    private val timeoutMs: Long = 60_000L
) {
    @Volatile
    private var worker: Thread? = null

    private val cancelled = AtomicBoolean(false)

    val isRunning: Boolean
        get() = worker?.isAlive == true

    @Synchronized
    fun execute(
        source: String,
        api: GgApiBridge,
        onOutput: (String) -> Unit,
        onFinished: (ScriptEndReason) -> Unit
    ) {
        if (isRunning) return
        cancelled.set(false)
        worker = thread(name = "mamu-lua-host", isDaemon = true) {
            val start = System.currentTimeMillis()
            val reason = runCatching {
                val debugLib = InterruptDebugLib {
                    cancelled.get() || System.currentTimeMillis() - start >= timeoutMs
                }
                val globals = SandboxGlobals.create(
                    onPrint = { line -> post { onOutput(line) } },
                    debugHook = debugLib
                )
                api.install(globals)
                globals.load(source, "script").call()
                when {
                    cancelled.get() -> ScriptEndReason.Stopped
                    System.currentTimeMillis() - start >= timeoutMs -> ScriptEndReason.Timeout
                    else -> ScriptEndReason.Completed
                }
            }.getOrElse { error ->
                when {
                    cancelled.get() -> ScriptEndReason.Stopped
                    System.currentTimeMillis() - start >= timeoutMs -> ScriptEndReason.Timeout
                    error is LuaError && error.message?.contains("script interrupted") == true -> {
                        if (cancelled.get()) ScriptEndReason.Stopped else ScriptEndReason.Timeout
                    }
                    else -> {
                        val line = parseLine(error.message)
                        ScriptEndReason.Error(error.message ?: error.toString(), line)
                    }
                }
            }
            post { onFinished(reason) }
            worker = null
        }
    }

    fun stop() {
        cancelled.set(true)
        val running = worker ?: return
        thread(name = "mamu-lua-stop", isDaemon = true) {
            try {
                running.join(2000)
            } catch (_: InterruptedException) {
            }
            if (running.isAlive) {
                running.interrupt()
            }
        }
    }

    private fun post(block: () -> Unit) {
        poster(Runnable { block() })
    }

    companion object {
        fun parseLine(message: String?): Int? {
            if (message.isNullOrBlank()) return null
            val match = Regex(""":(\d+)[:\]]""").find(message)
            return match?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }
}
