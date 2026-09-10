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

    // 当前会话的取消标志。execute() 启动新会话时创建独立实例,
    // 避免旧 worker(被 interrupt 但未退出)与新 worker 共享导致状态串扰。
    @Volatile
    private var cancelled: AtomicBoolean = AtomicBoolean(false)

    val isRunning: Boolean
        get() = worker?.isAlive == true

    @Synchronized
    fun execute(
        source: String,
        api: GgApiBridge,
        onOutput: (String) -> Unit,
        onFinished: (ScriptEndReason) -> Unit
    ) {
        // 若旧 worker 仍在 unwind(用户 Stop 后或新会话立即启动),interrupt 它并直接
        // 启动新 worker,避免 executeSource 因 isRunning=true 静默丢弃新会话。
        worker?.takeIf { it.isAlive }?.interrupt()
        // 为新会话创建独立的取消标志,旧 worker 持有旧引用不受影响。
        val myCancelled = AtomicBoolean(false)
        cancelled = myCancelled
        worker = thread(name = "mamu-lua-host", isDaemon = true) {
            val start = System.currentTimeMillis()
            val reason = runCatching {
                val shouldStop = {
                    myCancelled.get() || System.currentTimeMillis() - start >= timeoutMs
                }
                val debugLib = InterruptDebugLib(shouldStop)
                val globals = SandboxGlobals.create(
                    onPrint = { line -> post { onOutput(line) } },
                    debugHook = debugLib
                )
                api.shouldInterrupt = shouldStop
                api.install(globals)
                globals.load(source, "script").call()
                when {
                    myCancelled.get() -> ScriptEndReason.Stopped
                    System.currentTimeMillis() - start >= timeoutMs -> ScriptEndReason.Timeout
                    else -> ScriptEndReason.Completed
                }
            }.getOrElse { error ->
                when {
                    myCancelled.get() -> ScriptEndReason.Stopped
                    System.currentTimeMillis() - start >= timeoutMs -> ScriptEndReason.Timeout
                    error is LuaError && error.message?.contains("script interrupted") == true -> {
                        if (myCancelled.get()) ScriptEndReason.Stopped else ScriptEndReason.Timeout
                    }
                    else -> {
                        val line = parseLine(error.message)
                        ScriptEndReason.Error(error.message ?: error.toString(), line)
                    }
                }
            }
            post { onFinished(reason) }
            // 只有当前 worker 才能清空引用,避免旧 worker 退出时误清新 worker 的引用。
            synchronized(this) {
                if (worker === Thread.currentThread()) worker = null
            }
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
