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
import moe.fuqiuluo.mamu.script.ScriptEndReason
import moe.fuqiuluo.mamu.script.ScriptFsEntry
import moe.fuqiuluo.mamu.script.ScriptHost
import moe.fuqiuluo.mamu.script.ScriptLocalBrowser
import moe.fuqiuluo.mamu.script.ScriptMemoryRange
import moe.fuqiuluo.mamu.script.ScriptPaths
import moe.fuqiuluo.mamu.script.ScriptResultItem
import moe.fuqiuluo.mamu.script.ScriptUrlFetcher
import moe.fuqiuluo.mamu.widget.NotificationOverlay

class ScriptDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val coroutineScope: CoroutineScope,
    private val getSelectedResults: () -> List<ScriptResultItem>
) : BaseDialog(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val host = ScriptHost(poster = { mainHandler.post(it) })
    private lateinit var binding: DialogScriptBinding
    private lateinit var adapter: EntryAdapter
    private var outputCleared = false
    private var currentPath = ScriptPaths.DEFAULT_DIR

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
            appendOutput(context.getString(R.string.script_unbound))
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
        outputCleared = false
        binding.outputText.text = ""
        updateRunningState(true)
        val api = GgApiBridge(
            selectedResults = getSelectedResults(),
            onToast = { message ->
                mainHandler.post { notification.showWarning(message) }
            },
            onWarn = { message -> mainHandler.post { appendOutput(message) } },
            getResults = { maxCount ->
                val total = SearchEngine.getTotalResultCount().toInt().coerceAtLeast(0)
                val count = maxCount.coerceAtMost(total)
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
                SearchEngine.getTotalResultCount().toInt().coerceAtLeast(0)
            },
            onClearResults = { SearchEngine.clearSearchResults() },
            onGetMemoryRanges = { filter -> listMemoryRanges(filter) },
            onCopyText = { text -> copyText(text) },
            onFreeze = { addr, bytes, typeId -> FreezeManager.addFrozen(addr, bytes, typeId) },
            onUnfreeze = { addr -> FreezeManager.removeFrozen(addr) }
        )
        host.execute(
            source = source,
            api = api,
            onOutput = { line -> appendOutput(line) },
            onFinished = { reason ->
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
        if (!::binding.isInitialized) return
        val current = binding.outputText.text?.toString().orEmpty()
        val next = if (!outputCleared && current == context.getString(R.string.script_output_hint)) {
            line
        } else if (current.isEmpty()) {
            line
        } else {
            current + "\n" + line
        }
        outputCleared = true
        binding.outputText.text = next
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
        host.stop()
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
