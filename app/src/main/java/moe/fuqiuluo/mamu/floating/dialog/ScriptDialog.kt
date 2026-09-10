package moe.fuqiuluo.mamu.floating.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptBinding
import moe.fuqiuluo.mamu.driver.FreezeManager
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.script.GgApiBridge
import moe.fuqiuluo.mamu.script.GgApiBridge.Companion.toScriptResultItem
import moe.fuqiuluo.mamu.script.ScriptAlertRequest
import moe.fuqiuluo.mamu.script.ScriptChoiceRequest
import moe.fuqiuluo.mamu.script.ScriptEndReason
import moe.fuqiuluo.mamu.script.ScriptFsEntry
import moe.fuqiuluo.mamu.script.ScriptHost
import moe.fuqiuluo.mamu.script.ScriptLocalBrowser
import moe.fuqiuluo.mamu.script.ScriptMemoryRange
import moe.fuqiuluo.mamu.script.ScriptPaths
import moe.fuqiuluo.mamu.script.ScriptPromptRequest
import moe.fuqiuluo.mamu.script.ScriptResultItem
import moe.fuqiuluo.mamu.script.ScriptUrlFetcher
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.script.ScriptRegions
import moe.fuqiuluo.mamu.script.ScriptSearchStatus

class ScriptDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val coroutineScope: CoroutineScope,
    private val getSelectedResults: () -> List<ScriptResultItem>,
    private val onClearSearchResults: () -> Unit
) : BaseDialog(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val host = ScriptHost(poster = { mainHandler.post(it) })
    private lateinit var binding: DialogScriptBinding
    private lateinit var adapter: EntryAdapter
    private var currentPath = ScriptPaths.DEFAULT_DIR
    private var console: ScriptConsoleDialog? = null
    // 当前正在显示的交互弹窗(alert/choice/prompt)。脚本停止/父弹窗关闭时一并 dismiss,
    // 避免脚本已结束后交互弹窗仍悬浮或在新会话才弹出。
    @Volatile
    private var activeInteractive: BaseDialog? = null
    // 悬浮窗可见性:gg.isVisible 查询、gg.setVisible 切换。
    // 默认 true,让事件驱动脚本(while true + isVisible)首次就能进入 Main 流程,
    // 不用等用户手动呼出悬浮窗。
    private val overlayVisible = AtomicBoolean(true)
    // 标记 ScriptDialog 已 release。release 后排队的 appendOutput 不应再创建新控制台,
    // 否则会复活一个孤立的悬浮窗(脚本会话已结束)。
    @Volatile
    private var released = false
    // 暴露 released 状态供外部(SearchController)判断实例是否可复用:
    // 若已 released,重新打开应创建新实例而非 show() 复用(否则后续输出/交互被丢弃)。
    val isReleased: Boolean get() = released
    // 标记脚本被用户停止(Stop)。与 [released] 不同:ScriptDialog 仍开着,最后的输出
    // 仍应显示,但不应再显示新的交互弹窗(避免排队的 alert/choice/prompt 在停止后弹出)。
    // 新会话开始时(runLocalFile/runUrl/executeSource)重置为 false。
    @Volatile
    private var stopped = false
    // 会话代次(generation)。每次启动新会话自增,使被 Stop 的旧会话 IO 完成后
    // 因 epoch 不匹配而放弃执行,避免"新会话重置 stopped=true 后旧会话复活"。
    // 例如:脚本 A 加载中被 Stop(stopped=true),用户启动 B(stopped=false,epoch++),
    // A 的 IO 完成后核对 epoch 已变化,直接 return,不会执行 A。
    private val sessionEpoch = AtomicLong(0L)
    // gg.setRanges 使用的内存区域集合，初始沿用用户在悬浮窗里的选择。
    // 独立于 MMKV，避免脚本改动污染 UI 设置。
    private val scriptRanges: MutableSet<MemoryRange> =
        MMKV.defaultMMKV().selectedMemoryRanges.toMutableSet()
    // gg.getRanges 回显的 GG 位掩码。必须与 scriptRanges 保持同步，
    // 且初值由 scriptRanges 反推：否则脚本"保存 → 切换 → 用保存值恢复"时
    // 拿到的是 0，setRanges(0) 被拒绝，临时区域就残留下来了。
    @Volatile
    private var scriptRegionFlags: Int =
        ScriptRegions.fromRangeCodes(scriptRanges.map { it.code }.toSet())

    private fun shouldBlockInteractive(): Boolean = released || stopped

    val isRunning: Boolean
        get() = host.isRunning

    override fun setupDialog() {
        binding = DialogScriptBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()
        currentPath = ScriptPaths.normalize(
            mmkv.decodeString(LAST_DIR_KEY, ScriptPaths.DEFAULT_DIR) ?: ScriptPaths.DEFAULT_DIR
        )

        adapter = EntryAdapter { entry ->
            if (host.isRunning) return@EntryAdapter
            if (entry.isDirectory) {
                openDirectory(entry.path)
            } else {
                runLocalFile(entry.path)
            }
        }
        binding.entryList.layoutManager = LinearLayoutManager(context)
        binding.entryList.adapter = adapter

        if (!WuwaDriver.isProcessBound) {
            notification.showWarning(context.getString(R.string.script_unbound))
        }

        binding.btnUp.setOnClickListener {
            ScriptPaths.parent(currentPath)?.let { openDirectory(it) }
        }
        binding.btnRunUrl.setOnClickListener { runUrl() }
        binding.btnStop.setOnClickListener {
            // 标记停止:阻止排队中的交互弹窗在停止后弹出,并 dismiss 当前的。
            stopped = true
            mainHandler.post { dismissActiveInteractive() }
            host.stop()
        }
        binding.btnClose.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
        updateRunningState(false)
        openDirectory(currentPath)
    }

    private fun openDirectory(path: String) {
        val target = ScriptPaths.normalize(path)
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScriptLocalBrowser.list(target) }
            }
            result.onSuccess { entries ->
                currentPath = target
                MMKV.defaultMMKV().encode(LAST_DIR_KEY, currentPath)
                binding.pathText.text = currentPath
                adapter.setEntries(entries)
                val empty = entries.isEmpty()
                binding.entryList.visibility = if (empty) View.GONE else View.VISIBLE
                binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            }.onFailure { error ->
                notification.showWarning(error.message ?: context.getString(R.string.script_dir_failed))
            }
        }
    }

    private fun runLocalFile(path: String) {
        // 新会话启动点:自增 epoch 并重置 released/stopped 标志,确保后续输出进入控制台、
        // 交互弹窗正常显示。此处运行在主线程,与 release()/Stop 的写入互斥。
        val epoch = sessionEpoch.incrementAndGet()
        released = false
        stopped = false
        coroutineScope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching { ScriptLocalBrowser.read(path) }
            }.getOrElse { error ->
                // 仅当仍是当前会话时才报告错误,避免覆盖新会话输出。
                if (sessionEpoch.get() == epoch && !released) {
                    appendOutput(error.message ?: context.getString(R.string.script_read_failed))
                }
                return@launch
            }
            // 核对 epoch:若期间用户 Stop 后启动了新会话(或释放),放弃执行本会话。
            if (sessionEpoch.get() != epoch || shouldBlockInteractive()) return@launch
            appendOutput(context.getString(R.string.script_running_local, path))
            executeSource(source)
        }
    }

    private fun runUrl() {
        val url = binding.inputUrl.text?.toString().orEmpty()
        val invalid = ScriptUrlFetcher.validate(url)
        if (invalid != null) {
            appendOutput(invalid)
            return
        }
        // 不再因 host.isRunning 静默丢弃:ScriptHost.execute 会 interrupt 旧 worker。
        // 新会话启动点:同 runLocalFile,自增 epoch 并重置 released/stopped 再起协程。
        val epoch = sessionEpoch.incrementAndGet()
        released = false
        stopped = false
        coroutineScope.launch {
            appendOutput(context.getString(R.string.script_downloading))
            val source = withContext(Dispatchers.IO) {
                runCatching { ScriptUrlFetcher.fetch(url) }
            }.getOrElse { error ->
                if (sessionEpoch.get() == epoch && !released) {
                    appendOutput(error.message ?: context.getString(R.string.script_download_failed))
                }
                return@launch
            }
            if (sessionEpoch.get() != epoch || shouldBlockInteractive()) return@launch
            executeSource(source)
        }
    }

    private fun executeSource(source: String) {
        if (source.isBlank()) {
            appendOutput(context.getString(R.string.script_empty))
            return
        }
        // 不再因 host.isRunning 静默丢弃:ScriptHost.execute 会 interrupt 仍在 unwind 的
        // 旧 worker 并启动新会话,确保用户启动 B 时 B 优先执行而非被静默忽略。
        // 协程恢复时若会话已关闭或脚本已停止,直接放弃执行,避免启动孤立脚本与控制台。
        if (shouldBlockInteractive()) return
        // 切换脚本前重置悬浮窗可见性,避免上一会话 setVisible(false) 泄漏到新会话,
        // 导致事件驱动脚本(while true + isVisible)首次查询就拿到 false 而跳过 Main。
        // 注意:不在这里排队 show() —— 若脚本立即 setVisible(false),排队的 show()
        // 会在主线程恢复 overlayVisible=true 导致 hide() 的过期守卫把自己拒掉,
        // 最终窗口停留在 visible 状态。dialog 可见性由 SearchController.showScriptDialog()
        // 的 show() 负责(新/复用场景都经过它),executeSource 只重置 flag 保证状态一致。
        overlayVisible.set(true)
        // 切换脚本前清空控制台并关闭上一会话遗留的交互弹窗(若 A 正在 alert/choice/
        // prompt 等待,启动 B 时应关闭它,否则 A 的弹窗会在 B 期间悬浮且不被跟踪)。
        dismissActiveInteractive()
        // 切换脚本前清空控制台,新会话从空白开始。所有 console 访问统一在主线程。
        val previousConsole = console
        console = null
        previousConsole?.let { c -> mainHandler.post { c.dismiss() } }
        updateRunningState(true)
        // 捕获本次会话的 epoch,供 onOutput/onWarn/onFinished 回调在 post 块内核对,
        // 避免旧会话的排队回调写入新会话的控制台或应用错误的 finished 状态。
        val epoch = sessionEpoch.get()
        val api = GgApiBridge(
            selectedResults = getSelectedResults(),
            onToast = { message ->
                mainHandler.post {
                    if (sessionEpoch.get() != epoch || released) return@post
                    notification.showWarning(message)
                }
            },
            onWarn = { message ->
                mainHandler.post {
                    if (sessionEpoch.get() != epoch || released) return@post
                    appendOutput(message)
                }
            },
            getResults = { maxCount ->
                val count = GgApiBridge.clampResultLimit(
                    maxCount,
                    SearchEngine.getTotalResultCount()
                )
                if (count <= 0) emptyList() else {
                    SearchEngine.getResults(0, count).map { it.toScriptResultItem() }
                }
            },
            isProcessBound = { WuwaDriver.isProcessBound },
            currentPid = { WuwaDriver.currentBindPid },
            processName = {
                runCatching { WuwaDriver.getProcessInfo(WuwaDriver.currentBindPid).name }.getOrNull()
            },
            readMemory = { addr, size -> WuwaDriver.readMemory(addr, size) },
            writeMemory = { addr, data -> WuwaDriver.writeMemory(addr, data) },
            onGetResultsCount = {
                SearchEngine.getTotalResultCount().coerceAtLeast(0L)
            },
            onClearResults = onClearSearchResults,
            onGetMemoryRanges = { filter -> listMemoryRanges(filter) },
            onCopyText = { text -> copyText(text) },
            onFreeze = { addr, bytes, typeId -> FreezeManager.addFrozen(addr, bytes, typeId) },
            onUnfreeze = { addr -> FreezeManager.removeFrozen(addr) },
            onAlert = { request -> runBlockingDialog(epoch) { showAlertDialog(request, it) } },
            onChoice = { request ->
                runBlockingDialog(epoch) { showChoiceDialog(request, multiSelect = false, it) }?.firstOrNull()
            },
            onMultiChoice = { request ->
                runBlockingDialog(epoch) { showChoiceDialog(request, multiSelect = true, it) }
            },
            onPrompt = { request -> runBlockingDialog(epoch) { showPromptDialog(request, it) } },
            onIsVisible = { overlayVisible.get() },
            onStartSearch = { query, type -> startScriptSearch(query, type) },
            onStartRefine = { query, type -> startScriptRefine(query, type) },
            onSearchStatus = { scriptSearchStatus() },
            onCancelSearch = { runCatching { SearchEngine.requestCancel() } },
            onSetRanges = { flags -> applyScriptRanges(flags) },
            onGetRanges = { scriptRegionFlags },
            onEditAll = { bytes -> editAllResults(bytes) },
            onSetVisible = { v ->
                // 先写后校验(乐观模式):先写 overlayVisible,然后再检查 epoch/released。
                // 旧会话 worker 可能在检查与写入之间被抢占,而新会话 incrementAndGet 已执行。
                // 这种情况下旧会话的写入会覆盖新会话的状态,所以写入后必须再校验,
                // 若 epoch 已变则撤销写入恢复默认值。
                overlayVisible.set(v)
                val stale = sessionEpoch.get() != epoch || released
                if (stale) {
                    overlayVisible.set(true)
                } else {
                    mainHandler.post {
                        // 队列中的操作到主线程时再次校验:
                        // 1. epoch 仍匹配(防止 post 执行前又切换了会话)
                        // 2. overlayVisible 未被新操作覆盖(例如用户通过 show() 重开)
                        if (sessionEpoch.get() != epoch || released) return@post
                        if (overlayVisible.get() != v) return@post
                        // 注意:Android Dialog.hide() 不更新 isShowing,
                        // 所以不能用 isShowing 判断是否需要 show/hide —— 直接调用,幂等。
                        if (v) show() else dialog.hide()
                    }
                }
            }
        )
        host.execute(
            source = source,
            api = api,
            onOutput = { line ->
                mainHandler.post {
                    // 核对 epoch:旧会话排队的输出不写入新会话控制台。
                    if (sessionEpoch.get() != epoch || released) return@post
                    appendOutput(line)
                }
            },
            onFinished = { reason ->
                mainHandler.post {
                    // 旧会话的完成回调不应用到新会话:不 dismiss 其交互弹窗,
                    // 不写入其完成消息,不切换其运行状态。
                    if (sessionEpoch.get() != epoch || released) return@post
                    // 脚本结束:若仍有交互弹窗未关闭则关闭之,避免悬浮残留。
                    dismissActiveInteractive()
                    when (reason) {
                        ScriptEndReason.Completed -> appendOutput(context.getString(R.string.script_completed))
                        ScriptEndReason.Stopped -> appendOutput(context.getString(R.string.script_stopped))
                        ScriptEndReason.Timeout -> appendOutput(context.getString(R.string.script_timeout))
                        is ScriptEndReason.Error -> {
                            val prefix = if (reason.line != null) "错误: 行${reason.line}: " else "错误: "
                            appendOutput(prefix + reason.message)
                        }
                    }
                    updateRunningState(false)
                }
            }
        )
    }

    /**
     * 阻塞 Lua worker 线程直到主线程弹出 UI 并回调结果。
     * [show] 在主线程执行,其回调参数完成时计数 down,返回回调传入的值。
     * worker 在等待期间被中断时会抛 LuaError(由调用方处理 shouldInterrupt)。
     */
    private fun <T> runBlockingDialog(expectedEpoch: Long, show: (onResult: (T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>()
        mainHandler.post {
            // 会话已切换(epoch 不匹配)、已释放或脚本已停止:不再显示新弹窗,
            // 立即返回 null 解除 worker 阻塞,否则旧会话的 alert/choice/prompt
            // 会在新会话期间打开,阻塞或向已废弃的 worker 回传输入。
            if (sessionEpoch.get() != expectedEpoch || shouldBlockInteractive()) {
                result.set(null)
                latch.countDown()
                return@post
            }
            try {
                show { value ->
                    result.set(value)
                    latch.countDown()
                }
            } catch (e: Throwable) {
                result.set(null)
                latch.countDown()
            }
        }
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result.get()
    }

    private fun showAlertDialog(request: ScriptAlertRequest, onResult: (Int?) -> Unit) {
        showInteractive(ScriptAlertDialog(context, request, onResult))
    }

    private fun showChoiceDialog(
        request: ScriptChoiceRequest,
        multiSelect: Boolean,
        onResult: (List<Int>?) -> Unit
    ) {
        showInteractive(ScriptChoiceDialog(context, request, multiSelect, onResult))
    }

    private fun showPromptDialog(request: ScriptPromptRequest, onResult: (List<String>?) -> Unit) {
        showInteractive(ScriptPromptDialog(context, request, onResult))
    }

    /**
     * 跟踪当前交互弹窗。脚本停止或 ScriptDialog 关闭时通过 [dismissActiveInteractive]
     * 一并 dismiss,避免脚本结束后弹窗仍悬浮,或排队中的弹窗在父关闭后才弹出。
     * 各弹窗的 reported 守卫保证回调只会触发一次,故强制 dismiss 会安全返回 null。
     */
    private fun showInteractive(dialog: BaseDialog) {
        // 会话已释放或脚本已停止:不再显示新弹窗,直接 dismiss 该实例避免泄漏。
        if (shouldBlockInteractive()) {
            dialog.dismiss()
            return
        }
        activeInteractive = dialog
        dialog.onDismiss = {
            if (activeInteractive === dialog) activeInteractive = null
        }
        dialog.show()
    }

    private fun dismissActiveInteractive() {
        val d = activeInteractive
        activeInteractive = null
        d?.dismiss()
    }

    private fun copyText(text: String) {
        mainHandler.post {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@post
            clipboard.setPrimaryClip(ClipData.newPlainText("mamu-script", text))
        }
    }

    /**
     * 启动脚本发起的异步搜索。使用 [scriptRanges] 作为内存区域。
     */
    private fun startScriptSearch(query: String, type: DisplayValueType): Boolean {
        if (!WuwaDriver.isProcessBound) return false
        if (SearchEngine.isSearching()) return false
        return runCatching {
            SearchEngine.startSearchAsync(
                query = query,
                type = type,
                ranges = scriptRanges.toSet(),
                useDeepSearch = false
            )
        }.getOrDefault(false)
    }

    /**
     * 在上次结果基础上细化搜索。
     */
    private fun startScriptRefine(query: String, type: DisplayValueType): Boolean {
        if (!WuwaDriver.isProcessBound) return false
        if (SearchEngine.isSearching()) return false
        return runCatching {
            SearchEngine.startRefineAsync(query = query, type = type)
        }.getOrDefault(false)
    }

    /**
     * 把搜索引擎的共享缓冲区状态翻译成桥接层可理解的结果。
     */
    private fun scriptSearchStatus(): ScriptSearchStatus {
        return when (SearchEngine.getStatus()) {
            // 状态位 published 早于 native 的 JoinHandle 结束，而启动新搜索前
            // 的 busy 检查看的是那个 handle。若这里只看状态位就返回完成，
            // 紧随其后的 refineNumber / searchNumber 可能撞上 busy 而失败，
            // 顺序执行的脚本会莫名其妙中断。所以两个条件都要满足。
            SearchEngine.Status.COMPLETED ->
                ScriptSearchStatus(!SearchEngine.isSearching(), SearchEngine.getTotalResultCount(), null)

            SearchEngine.Status.CANCELLED ->
                ScriptSearchStatus(true, 0L, "搜索已取消")

            SearchEngine.Status.ERROR ->
                ScriptSearchStatus(true, 0L, "搜索失败，错误码 ${SearchEngine.getErrorCode()}")

            else ->
                ScriptSearchStatus(false, SearchEngine.getFoundCount().coerceAtLeast(0L), null)
        }
    }

    /**
     * 把同一段字节写入当前结果列表的全部条目。
     *
     * 必须分页：一次 getResults 受 MAX_GET_RESULTS 上限约束，而 DWORD 搜 0
     * 这类宽搜索的匹配数远超该值。gg.editAll 承诺改写全部结果，
     * 只写第一页会静默漏掉后面的匹配。
     */
    private fun editAllResults(bytes: ByteArray): Int {
        if (!WuwaDriver.isProcessBound) return 0
        val total = SearchEngine.getTotalResultCount().coerceAtLeast(0L)
        if (total <= 0L) return 0
        var written = 0
        var offset = 0
        while (offset < total) {
            val count = minOf(EDIT_ALL_PAGE_SIZE.toLong(), total - offset).toInt()
            val page = runCatching { SearchEngine.getResults(offset, count) }.getOrNull()
                ?: break
            if (page.isEmpty()) break
            page.forEach { item ->
                if (WuwaDriver.writeMemory(item.address, bytes)) written++
            }
            offset += count
        }
        return written
    }

    private fun applyScriptRanges(flags: Int): Boolean {
        val ranges = ScriptRegions.toRangeCodes(flags)
            .mapNotNull { MemoryRange.fromCode(it) }
            .toSet()
        if (ranges.isEmpty()) return false
        scriptRanges.clear()
        scriptRanges.addAll(ranges)
        scriptRegionFlags = flags
        return true
    }

    private fun listMemoryRanges(filter: String?): List<ScriptMemoryRange> {
        if (!WuwaDriver.isProcessBound) return emptyList()
        val regions = runCatching { WuwaDriver.queryMemRegionsWithRetry() }.getOrNull() ?: return emptyList()
        return regions.map { region ->
            ScriptMemoryRange(
                start = region.start,
                end = region.end,
                name = region.name,
                state = region.permissionString
            )
        }.filter { range ->
            filter.isNullOrBlank() || range.name.contains(filter, ignoreCase = true)
        }
    }

    private fun appendOutput(line: String) {
        // 输出统一进入弹出式控制台,与官方 GG 行为一致。
        // 调用方(onOutput/onWarn/onFinished/executeSource)已在主线程并通过 epoch 检查,
        // 此处不再 mainHandler.post,避免嵌套 post 绕过 epoch 守卫导致跨会话串扰。
        // release 后的输出直接丢弃,避免复活已关闭的控制台导致孤立悬浮窗。
        if (released) return
        val c = console ?: ScriptConsoleDialog(context).also { newConsole ->
            // 用户点关闭按钮或返回键后,清掉缓存引用,使下次输出会重新弹出新窗口,
            // 而不是继续往已 dismiss 的视图里追加(否则后续输出不可见)。
            newConsole.onDismiss = {
                if (console === newConsole) console = null
            }
            newConsole.show()
            console = newConsole
        }
        c.append(line)
    }

    private fun updateRunningState(running: Boolean) {
        if (!::binding.isInitialized) return
        binding.btnRunUrl.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.inputUrl.isEnabled = !running
        binding.btnUp.isEnabled = !running
        binding.entryList.isEnabled = !running
    }

    fun release() {
        // 标记已释放:后续排队的 appendOutput 不再复活控制台。
        released = true
        host.stop()
        // 关闭当前交互弹窗(若有),避免脚本停止后悬浮残留。
        mainHandler.post { dismissActiveInteractive() }
        console?.let { c -> mainHandler.post { c.dismiss() } }
        console = null
    }

    override fun dismiss() {
        release()
        super.dismiss()
    }

    /**
     * 重写 show():无论由用户打开悬浮窗还是脚本内部 gg.setVisible(true) 触发,
     * 都同步 overlayVisible = true,避免"窗口已显示但 isVisible() 仍返回 false"
     * 的状态不同步(例如脚本先 setVisible(false) 后被用户重新打开)。
     */
    override fun show() {
        overlayVisible.set(true)
        super.show()
    }

    private class EntryAdapter(
        private val onClick: (ScriptFsEntry) -> Unit
    ) : RecyclerView.Adapter<EntryAdapter.ViewHolder>() {
        private val entries = mutableListOf<ScriptFsEntry>()

        fun setEntries(items: List<ScriptFsEntry>) {
            entries.clear()
            entries.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_script_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position], onClick)
        }

        override fun getItemCount(): Int = entries.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.entry_icon)
            private val name: TextView = itemView.findViewById(R.id.entry_name)
            private val meta: TextView = itemView.findViewById(R.id.entry_meta)

            fun bind(entry: ScriptFsEntry, onClick: (ScriptFsEntry) -> Unit) {
                name.text = entry.name
                if (entry.isDirectory) {
                    icon.setImageResource(R.drawable.icon_folder_24px)
                    meta.text = itemView.context.getString(R.string.script_entry_dir)
                } else {
                    icon.setImageResource(R.drawable.icon_list_24px)
                    meta.text = formatSize(entry.size)
                }
                itemView.setOnClickListener { onClick(entry) }
            }

            private fun formatSize(size: Long): String {
                return when {
                    size < 1024 -> "$size B"
                    size < 1024 * 1024 -> "${size / 1024} KB"
                    else -> "${size / (1024 * 1024)} MB"
                }
            }
        }
    }

    companion object {
        private const val LAST_DIR_KEY = "script_browser_last_dir"
        // gg.editAll 分页写入时每页的条目数。
        private const val EDIT_ALL_PAGE_SIZE = 4096
    }
}
