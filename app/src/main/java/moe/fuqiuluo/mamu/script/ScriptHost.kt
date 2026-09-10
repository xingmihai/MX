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

    // 单线程执行器:串行处理所有脚本执行请求,保证任意时刻只有一个 worker 运行,
    // 不会出现多个 replace 调度线程并行 join 同一 previous 后并发 startWorker。
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mamu-lua-executor").apply { isDaemon = true }
    }

    val isRunning: Boolean
        get() = worker?.isAlive == true

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
        // 该 join 实际上是有界的,不会无限阻塞 executor:
        //  1. 纯 Lua 代码:debug hook 每行检查 shouldStop(= myCancelled || 超过 timeoutMs),
        //     worker 最迟在自身 timeoutMs(60s)内自行终止——故 join 最多等到 timeoutMs。
        //  2. 响应中断的 Java 阻塞调用(sleep/wait/可中断 I/O):execute 已 interrupt,
        //     它们抛 InterruptedException 立即退出——join 几乎立即返回。
        //  3. API 桥接调用:均检查 api.shouldInterrupt,取消后随即返回。
        // 仅当 worker 卡在"既不响应 interrupt、又不检查 shouldInterrupt、且永不返回"的
        // native 死循环/死锁(JVM 无法安全强杀线程,native 代码自身缺陷)时,join 才会
        // 无限等待——但这不会导致两个 worker 并发修改共享状态(worker 仍卡在 native
        // 调用中,不会执行任何 Lua/API 操作),仅影响后续替换脚本的启动。这是 JVM
        // 线程模型的固有限制,无法在不引入并发风险的前提下完全消除。
        val previous = worker
        if (previous != null && previous.isAlive) {
            try {
                previous.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
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
                    error is ScriptExit -> ScriptEndReason.Completed
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
