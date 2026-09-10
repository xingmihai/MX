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
    // 注意:execute() 会先把 [cancelled] 置 true(取消上一个请求)再切换到新标志,
    // 因此历史请求的标志会被依次取消;但 [cancelled] 始终指向最近一次请求,
    // 在"A 仍在 unwind、B/C 已排队"时 [cancelled] 指向 C 而非正在跑的 A。
    // 故 stop() 另用 [runningCancel] 精确取消正在执行的 worker。
    @Volatile
    private var cancelled: AtomicBoolean = AtomicBoolean(false)

    // 当前正在运行(或即将运行)的 worker 的取消标志。startWorker 启动 worker 前置入,
    // stop() 据此精确取消正在执行的 worker,而非最近一次 execute() 排队的请求。
    @Volatile
    private var runningCancel: AtomicBoolean? = null

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
        // 取消所有遗留会话,确保新会话开始前既无运行中 worker 也无排队请求存活:
        //  - runningCancel 精确取消正在执行的 worker(即使最近 execute 排队了新请求
        //    使 [cancelled] 指向队列尾,runningCancel 仍指向真正在跑的 worker);
        //  - cancelled 取消最近一次(可能仍在排队、尚未 startWorker)的请求。
        // 二者合覆盖所有历史请求,避免中断不敏感的旧 worker 漏网继续到超时。
        runningCancel?.set(true)
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
        // 等待上一 worker 退出再启动新 worker,保证不并行。
        // 正常情况下旧 worker 在 execute() 中已被置 shouldStop=true 并 interrupt:
        // 纯 Lua 代码在 debug hook(每行检查 shouldStop)下立即退出;响应中断的阻塞
        // 调用(sleep/wait/可中断 I/O)也会随即抛 InterruptedException 退出。
        // 使用有界 join 而非无限 join,避免旧 worker 卡在中断不敏感且不检查
        // shouldInterrupt 的死操作(native 死循环/死锁)时无限阻塞,导致替换脚本永不执行。
        val previous = worker
        if (previous != null && previous.isAlive) {
            try {
                previous.join(timeoutMs + 2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // 超时仍未退出:旧 worker 为中断不敏感的死操作所卡(极罕见)。此时放行
            // 新会话而非无限阻塞或丢弃用户请求,安全性由以下保证:
            //  - 旧 worker 的 myCancelled 已为 true(execute 中设置),其一旦恢复执行,
            //    debug hook 每行检查 shouldStop 与 api.shouldInterrupt 都会令其 abort,
            //    不会与新 worker 并发修改共享搜索/内存/冻结状态;
            //  - 旧 worker 退出时通过 `worker === Thread.currentThread()` 守卫不会误清
            //    新 worker 引用,后续 stop() 也能正确指向新 worker。
            // 不再 re-interrupt:execute 中已 interrupt 过,且对死操作无效。
        }
        // 记录本 worker 的取消标志为"运行中"的标志,供 stop() 精确取消正在执行的
        // worker(而非最近排队的请求)。先置标志再启动 worker,保证 stop 可见。
        runningCancel = myCancelled
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
                if (worker === Thread.currentThread()) {
                    worker = null
                    if (runningCancel === myCancelled) runningCancel = null
                }
            }
        }
        // 不在此 join 本 worker:下一个 startWorker 开头会检查并 join 仍 alive 的
        // worker,保证串行。executor 任务立即返回,不占用 executor 线程等待。
    }

    fun stop() {
        // 精确取消正在执行的 worker:即使最近一次 execute() 排队了新请求(C)使
        // [cancelled] 指向 C,这里仍取消真正在跑的 worker 的标志(runningCancel),
        // 避免中断不敏感的旧 worker 继续到超时并修改共享状态。
        runningCancel?.set(true)
        // 同时取消最近排队的请求,使其启动后立即退出。
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
