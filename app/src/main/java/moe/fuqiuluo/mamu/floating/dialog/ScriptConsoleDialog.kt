package moe.fuqiuluo.mamu.floating.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.widget.ScrollView
import android.widget.Toast
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptConsoleBinding

/**
 * 脚本输出控制台。替代 ScriptDialog 内嵌的输出框:
 * 脚本运行期间的 print/toast/警告/错误统一显示在此弹窗,
 * 与官方 GG 行为一致。
 *
 * 调用方通过 [append] 追加行;关闭后由调用方决定是否再 show()。
 */
class ScriptConsoleDialog(context: Context) : BaseDialog(context) {

    private lateinit var binding: DialogScriptConsoleBinding
    private val lines = StringBuilder()

    override fun setupDialog() {
        binding = DialogScriptConsoleBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()

        binding.consoleText.movementMethod = ScrollingMovementMethod.getInstance()
        refreshText()

        binding.btnClear.setOnClickListener {
            lines.setLength(0)
            refreshText()
        }
        binding.btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("mamu-script-output", lines.toString())
            )
            Toast.makeText(context, context.getString(R.string.script_console_copy), Toast.LENGTH_SHORT).show()
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }

    fun append(line: String) {
        if (!::binding.isInitialized) return
        if (lines.isNotEmpty()) lines.append('\n')
        lines.append(line)
        refreshText()
        binding.consoleScroll.post { binding.consoleScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun refreshText() {
        if (!::binding.isInitialized) return
        binding.consoleText.text = if (lines.isEmpty()) {
            context.getString(R.string.script_console_empty)
        } else {
            lines.toString()
        }
    }
}
