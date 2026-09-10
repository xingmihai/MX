package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptPromptBinding
import moe.fuqiuluo.mamu.script.ScriptPromptRequest

/**
 * gg.prompt 输入弹窗。按 [ScriptPromptRequest.types] 渲染对应输入类型。
 * 返回与 labels 等长的字符串列表,用户取消返回 null。
 */
class ScriptPromptDialog(
    context: Context,
    private val request: ScriptPromptRequest,
    private val onResult: (List<String>?) -> Unit
) : BaseDialog(context) {

    private var reported = false

    override fun setupDialog() {
        val binding = DialogScriptPromptBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()

        if (request.labels.isEmpty()) {
            binding.btnOk.isEnabled = false
        }
        val adapter = PromptAdapter(request)
        binding.promptFields.adapter = adapter

        binding.btnOk.setOnClickListener {
            finish(adapter.getValues())
        }
        binding.btnCancel.setOnClickListener { finish(null) }
        onDismiss = { finish(null) }
    }

    private fun finish(result: List<String>?) {
        if (reported) return
        reported = true
        onResult(result)
        dismiss()
    }

    private class PromptAdapter(private val request: ScriptPromptRequest) :
        RecyclerView.Adapter<PromptAdapter.ViewHolder>() {

        // 按位置记录当前绑定的 EditText,以便读取用户输入。
        private val editors = mutableMapOf<Int, EditText>()

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(android.R.id.text1)
            val input: EditText = itemView.findViewById(android.R.id.edit)
            var boundPosition: Int = RecyclerView.NO_POSITION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(moe.fuqiuluo.mamu.R.layout.item_script_prompt_field, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // 持有者被复用前清掉旧位置映射,避免读到陈旧引用。
            if (holder.boundPosition != RecyclerView.NO_POSITION) {
                editors.remove(holder.boundPosition)
            }
            holder.boundPosition = position
            editors[position] = holder.input
            val label = request.labels[position]
            val default = request.defaults.getOrNull(position).orEmpty()
            val type = request.types.getOrNull(position) ?: "text"
            holder.label.text = label
            holder.input.setText(default)
            holder.input.inputType = when (type) {
                "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                "decimal", "float", "double" ->
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
            holder.input.imeOptions = if (position == request.labels.size - 1) {
                EditorInfo.IME_ACTION_DONE
            } else {
                EditorInfo.IME_ACTION_NEXT
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            if (holder.boundPosition != RecyclerView.NO_POSITION) {
                editors.remove(holder.boundPosition)
                holder.boundPosition = RecyclerView.NO_POSITION
            }
        }

        fun getValues(): List<String> = (0 until request.labels.size).map { idx ->
            editors[idx]?.text?.toString().orEmpty()
        }
    }
}
