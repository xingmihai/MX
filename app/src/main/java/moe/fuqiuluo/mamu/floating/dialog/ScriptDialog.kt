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
import java.util.concurrent.atomic.AtomicReference

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
    // 标记 ScriptDialog 已 release。release 后排队的 appendOutput 不应再创建新控制台,
    // 否则会复活一个孤立的悬浮窗(脚本会话已结束)。
    @Volatile
    private var released = false

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
        binding.btnStop.setOnClickListener { host.stop() }
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
        // 新会话启动点:重置 released 标志,确保后续输出进入控制台。
        // 此处运行在主线程,与 release() 的 released=true 互斥,不会出现重置先于关闭的竞态。
        released = false
        coroutineScope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching { ScriptLocalBrowser.read(path) }
            }.getOrElse { error ->
                appendOutput(error.message ?: context.getString(R.string.script_read_failed))
                return@launch
            }
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
        if (host.isRunning) return
        // 新会话启动点:同 runLocalFile,先重置 released 再起协程。
        released = false
        coroutineScope.launch {
            appendOutput(context.getString(R.string.script_downloading))
            val source = withContext(Dispatchers.IO) {
                runCatching { ScriptUrlFetcher.fetch(url) }
            }.getOrElse { error ->
                appendOutput(error.message ?: context.getString(R.string.script_download_failed))
                return@launch
            }
            executeSource(source)
        }
    }

    private fun executeSource(source: String) {
        if (source.isBlank()) {
            appendOutput(context.getString(R.string.script_empty))
            return
        }
        if (host.isRunning) return
        // 协程恢复时若会话已关闭,直接放弃执行,避免启动孤立脚本与控制台。
        if (released) return
        // 切换脚本前清空控制台,新会话从空白开始。所有 console 访问统一在主线程。
        val previousConsole = console
        console = null
        previousConsole?.let { c -> mainHandler.post { c.dismiss() } }
        updateRunningState(true)
        val api = GgApiBridge(
            selectedResults = getSelectedResults(),
            onToast = { message ->
                mainHandler.post { notification.showWarning(message) }
            },
            onWarn = { message -> mainHandler.post { appendOutput(message) } },
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
            onAlert = { request -> runBlockingDialog { showAlertDialog(request, it) } },
            onChoice = { request ->
                runBlockingDialog { showChoiceDialog(request, multiSelect = false, it) }?.firstOrNull()
            },
            onMultiChoice = { request ->
                runBlockingDialog { showChoiceDialog(request, multiSelect = true, it) }
            },
            onPrompt = { request -> runBlockingDialog { showPromptDialog(request, it) } }
        )
        host.execute(
            source = source,
            api = api,
            onOutput = { line -> appendOutput(line) },
            onFinished = { reason ->
                // 脚本结束:若仍有交互弹窗未关闭则关闭之,避免悬浮残留。
                mainHandler.post { dismissActiveInteractive() }
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
        )
    }

    /**
     * 阻塞 Lua worker 线程直到主线程弹出 UI 并回调结果。
     * [show] 在主线程执行,其回调参数完成时计数 down,返回回调传入的值。
     * worker 在等待期间被中断时会抛 LuaError(由调用方处理 shouldInterrupt)。
     */
    private fun <T> runBlockingDialog(show: (onResult: (T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>()
        mainHandler.post {
            // 会话已释放:不再显示新弹窗,立即返回 null 解除 worker 阻塞,
            // 否则排队的 show 会在父弹窗关闭后创建孤立悬浮窗。
            if (released) {
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
        // 会话已释放:不再显示新弹窗,直接 dismiss 该实例避免泄漏。
        if (released) {
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
        mainHandler.post {
            // release 后排队的输出直接丢弃,避免复活已关闭的控制台导致孤立悬浮窗。
            if (released) return@post
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
    }
}
