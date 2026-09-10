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

    /**
     * 提交一个脚本执行请求。若已有 worker 在运行,旧 worker 会被标记取消并等待真正
     * 退出后,新 worker 才在同一调度线程上启动,保证任意时刻只有一个 worker 运行
     * (无并行)。execute 立即返回,不阻塞主线程。新会话不会被静默丢弃:它一定
     * 会在旧 worker 退出后执行。
     */
    @Synchronized
    fun execute(
        source: String,
        api: GgApiBridge,
        onOutput: (String) -> Unit,
        onFinished: (ScriptEndReason) -> Unit
    ) {
        val myCancelled = AtomicBoolean(false)
        cancelled = myCancelled
        val previous = worker
        if (previous != null && previous.isAlive) {
            // 提示旧 worker 尽快到达取消点退出。
            previous.interrupt()
            // 在调度线程里等待旧 worker 真正退出,再启动新 worker,保证不并行。
            // 注意:不检查 worker !== previous 放弃,否则新会话会被静默丢弃。
            // 即便期间又提交了 C,C 的 execute 会再次进入这里,把 C 排在 B 之后,
            // 由同一调度链串行处理(B 启动后 C 再等 B)。
            thread(name = "mamu-lua-replace", isDaemon = true) {
                try { previous.join() } catch (_: InterruptedException) {
                    // 调度线程被中断,放弃本次替换(极端情况)
                    return@thread
                }
                startWorker(source, api, onOutput, onFinished, myCancelled)
            }
        } else {
            startWorker(source, api, onOutput, onFinished, myCancelled)
        }
    }

    private fun startWorker(
        source: String,
        api: GgApiBridge,
        onOutput: (String) -> Unit,
        onFinished: (ScriptEndReason) -> Unit,
        myCancelled: AtomicBoolean
    ) {
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
