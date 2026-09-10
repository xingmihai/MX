package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
 *
 * 为避免 RecyclerView 回收离屏字段后丢失用户已输入的值,getValues() 从持久化
 * [values] 读取,而非从当前绑定的 EditText 读取。绑定/回收时同步刷新 [values]。
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

        // 持久化每个字段的当前值,绑定/回收/输入时同步,使 getValues() 不依赖
        // 当前是否在屏上,避免离屏字段被回收后丢失已输入或默认值。
        private val values: MutableList<String> =
            (0 until request.labels.size).map { request.defaults.getOrNull(it).orEmpty() }.toMutableList()

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(android.R.id.text1)
            val input: EditText = itemView.findViewById(android.R.id.edit)
            var boundPosition: Int = RecyclerView.NO_POSITION
            var watcher: TextWatcher? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(moe.fuqiuluo.mamu.R.layout.item_script_prompt_field, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // 复用前先卸载旧位置的 watcher,避免回调到错误位置。
            holder.watcher?.let { holder.input.removeTextChangedListener(it) }
            holder.boundPosition = position
            val label = request.labels[position]
            val type = request.types.getOrNull(position) ?: "text"
            holder.label.text = label
            holder.input.setText(values[position])
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
            val tw = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (holder.boundPosition != RecyclerView.NO_POSITION) {
                        values[holder.boundPosition] = s?.toString().orEmpty()
                    }
                }
            }
            holder.input.addTextChangedListener(tw)
            holder.watcher = tw
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.watcher?.let { holder.input.removeTextChangedListener(it) }
            holder.watcher = null
            holder.boundPosition = RecyclerView.NO_POSITION
        }

        override fun getItemCount(): Int = request.labels.size

        fun getValues(): List<String> = values.toList()
    }
}
