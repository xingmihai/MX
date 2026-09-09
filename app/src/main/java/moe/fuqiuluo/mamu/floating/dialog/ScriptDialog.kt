package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptBinding
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.script.GgApiBridge
import moe.fuqiuluo.mamu.script.GgApiBridge.Companion.toScriptResultItem
import moe.fuqiuluo.mamu.script.ScriptEndReason
import moe.fuqiuluo.mamu.script.ScriptHost
import moe.fuqiuluo.mamu.script.ScriptRepository
import moe.fuqiuluo.mamu.script.ScriptResultItem
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class ScriptDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val getSelectedResults: () -> List<ScriptResultItem>,
    private val repository: ScriptRepository = ScriptRepository(context)
) : BaseDialog(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val host = ScriptHost(poster = { mainHandler.post(it) })
    private lateinit var binding: DialogScriptBinding
    private var outputCleared = false

    val isRunning: Boolean
        get() = host.isRunning

    override fun setupDialog() {
        binding = DialogScriptBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()

        val draft = repository.loadDraft()
        if (draft.isNotEmpty()) {
            binding.inputScript.setText(draft)
        }

        binding.inputScript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                repository.saveDraft(s?.toString().orEmpty())
            }
        })

        if (!WuwaDriver.isProcessBound) {
            appendOutput(context.getString(R.string.script_unbound))
        }

        binding.btnRun.setOnClickListener { runScript() }
        binding.btnStop.setOnClickListener { host.stop() }
        binding.btnSave.setOnClickListener { saveScript() }
        binding.btnLoad.setOnClickListener { loadScript() }
        binding.btnClose.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
        updateRunningState(false)
    }

    private fun runScript() {
        val source = binding.inputScript.text?.toString().orEmpty()
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
            onToast = { message -> notification.showSuccess(message) },
            onWarn = { message -> appendOutput(message) },
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
            writeMemory = { addr, data -> WuwaDriver.writeMemory(addr, data) }
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

    private fun saveScript() {
        val source = binding.inputScript.text?.toString().orEmpty()
        if (source.isBlank()) {
            appendOutput(context.getString(R.string.script_empty))
            return
        }
        val fileName = binding.inputFileName.text?.toString().orEmpty()
        runCatching {
            repository.save(fileName, source)
        }.onSuccess { saved ->
            binding.inputFileName.setText(saved)
            notification.showSuccess(context.getString(R.string.script_saved, saved))
        }.onFailure {
            notification.showWarning(context.getString(R.string.script_invalid_file_name))
        }
    }

    private fun loadScript() {
        val files = repository.list()
        if (files.isEmpty()) {
            notification.showWarning(context.getString(R.string.script_no_saved))
            return
        }
        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.script_load_title),
            options = files.toTypedArray(),
            onSingleChoice = { index ->
                val name = files[index]
                runCatching { repository.load(name) }.onSuccess { content ->
                    binding.inputFileName.setText(name)
                    binding.inputScript.setText(content)
                    repository.saveDraft(content)
                }
            }
        )
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
        binding.btnRun.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.inputScript.isEnabled = !running
    }

    fun release() {
        host.stop()
        if (::binding.isInitialized) {
            repository.saveDraft(binding.inputScript.text?.toString().orEmpty())
        }
    }

    override fun dismiss() {
        release()
        super.dismiss()
    }
}
