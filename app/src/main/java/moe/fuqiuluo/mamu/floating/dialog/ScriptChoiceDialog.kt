package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptChoiceBinding
import moe.fuqiuluo.mamu.script.ScriptChoiceRequest

/**
 * 列表选择弹窗,同时支持 gg.choice(单选) 和 gg.multiChoice(多选)。
 * [multiSelect]=false 时返回 [List] 包含单个元素(选中的 1-based 索引),用户取消返回 null。
 * [multiSelect]=true 时返回所有选中的 1-based 索引列表,取消返回 null。
 */
class ScriptChoiceDialog(
    context: Context,
    private val request: ScriptChoiceRequest,
    private val multiSelect: Boolean,
    private val onResult: (List<Int>?) -> Unit
) : BaseDialog(context) {

    private var reported = false
    private val checked = BooleanArray(request.items.size).apply {
        request.selected?.let { if (it in indices) set(it - 1, true) }
    }

    override fun setupDialog() {
        val binding = DialogScriptChoiceBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()

        binding.choiceTitle.text = request.message
            ?: context.getString(R.string.script_choice_default_message)
        binding.btnOk.visibility = if (multiSelect) View.VISIBLE else View.GONE

        val adapter = ChoiceAdapter(
            items = request.items,
            multiSelect = multiSelect,
            checked = checked,
            onClick = { position ->
                if (multiSelect) {
                    checked[position] = !checked[position]
                    binding.choiceList.adapter?.notifyItemChanged(position)
                } else {
                    finish(listOf(position + 1))
                }
            }
        )
        binding.choiceList.adapter = adapter

        binding.btnOk.setOnClickListener {
            val selected = (1..request.items.size).filter { checked[it - 1] }
            finish(selected)
        }
        binding.btnCancel.setOnClickListener { finish(null) }
        onDismiss = { finish(null) }
    }

    private fun finish(result: List<Int>?) {
        if (reported) return
        reported = true
        onResult(result)
        dismiss()
    }

    private class ChoiceAdapter(
        private val items: List<String>,
        private val multiSelect: Boolean,
        private val checked: BooleanArray,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChoiceAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(android.R.id.text1)
            val check: View = itemView.findViewById(android.R.id.checkbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_script_choice, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.label.text = items[position]
            holder.check.visibility = if (multiSelect && checked[position]) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size
    }
}
