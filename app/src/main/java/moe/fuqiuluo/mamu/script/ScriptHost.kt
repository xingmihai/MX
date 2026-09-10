package moe.fuqiuluo.mamu.script

import org.luaj.vm2.LuaError
import java.util.concurrent.Executors
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

    // 标记本 host 存在无法终止的"卡死" worker:join 超时后置 true,
    // 由 ScriptDialog 检测并创建全新 ScriptHost 实例启动后续脚本,
    // 避免无限阻塞 executor 使替换脚本永不执行。卡死 worker 持有旧 api,
    // 其 shouldInterrupt 已为 true,一旦从 native 调用返回即 abort。
    @Volatile
    private var stuck = false

    // 单线程执行器:串行处理所有脚本执行请求,保证任意时刻只有一个 worker 运行,
    // 不会出现多个 replace 调度线程并行 join 同一 previous 后并发 startWorker。
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mamu-lua-executor").apply { isDaemon = true }
    }

    val isRunning: Boolean
        get() = worker?.isAlive == true

    /** 本 host 是否存在无法终止的卡死 worker,调用方据此决定是否换新实例。 */
    val hasStuckWorker: Boolean
        get() = stuck

    /**
     * 提交一个脚本执行请求到串行执行器。由于 executor 是单线程,多个 execute
     * 请求会排队依次执行:前一个任务(含旧 worker 的运行)完成后,下一个任务
     * 才开始。这从根本上保证不并行,也保证新会话不被静默丢弃。
     * execute 立即返回,不阻塞主线程。
     */
    @Synchronized
    fun execute(
        source: String,
        api: GgApiBridge,
        onOutput: (String) -> Unit,
        onFinished: (ScriptEndReason) -> Unit
    ) {
        val myCancelled = AtomicBoolean(false)
        // 先把旧会话的取消标志置 true 并 interrupt 旧 worker,让它尽快退出。
        // 注意:必须用旧的 cancelled 引用(此时还未被 myCancelled 覆盖)。
        cancelled.set(true)
        worker?.takeIf { it.isAlive }?.interrupt()
        // 切换到新会话的取消标志。
        cancelled = myCancelled
        executor.submit {
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
        // 等待上一 worker 退出再启动新 worker,保证任意时刻只有一个 worker 运行(无重叠)。
        // 正常情况下旧 worker 几乎立即退出(execute 已置 cancelled=true 并 interrupt):
        //  1. 纯 Lua 代码:debug hook 每行检查 shouldStop,立即终止。
        //  2. 响应中断的 Java 阻塞调用(sleep/wait/可中断 I/O):抛 InterruptedException 退出。
        //  3. API 桥接调用:检查 api.shouldInterrupt,取消后随即返回。
        // 采用有界 join 防止 worker 卡在"既不响应 interrupt、又不检查 shouldInterrupt、
        // 且永不返回"的 native 死循环(JVM 无法安全强杀线程)时无限阻塞 executor。
        // 超时后不直接放行新 worker(那会引入同 host 内两 worker 并发风险),而是标记
        // stuck 让 ScriptDialog 层创建全新 ScriptHost 实例启动后续脚本——新 host 与
        // 旧 host 的内部状态(worker/cancelled/executor)完全隔离,无串扰;卡死 worker
        // 持有旧 api,shouldInterrupt 已为 true,一旦从 native 返回即 abort,不修改共享状态。
        val previous = worker
        if (previous != null && previous.isAlive) {
            try {
                previous.join(timeoutMs + 2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (previous.isAlive) {
                // 旧 worker 在 timeoutMs+2s 后仍未退出:判定为卡死(native 死循环/死锁)。
                // 标记本 host stuck,放弃追踪该 worker,由调用方换新 host。
                stuck = true
                return
            }
        }
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
        // 不在此 join 本 worker:下一个 startWorker 开头会检查并 join 仍 alive 的
        // worker,保证串行。executor 任务立即返回,不占用 executor 线程等待。
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
