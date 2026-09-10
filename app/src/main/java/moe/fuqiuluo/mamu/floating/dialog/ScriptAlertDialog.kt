package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogScriptAlertBinding
import moe.fuqiuluo.mamu.script.ScriptAlertRequest

/**
 * gg.alert 弹窗。最多展示 positive/negative/neutral 三个按钮。
 * 返回值约定: 1=positive, 2=negative, 3=neutral, null=用户关闭未选。
 */
class ScriptAlertDialog(
    context: Context,
    private val request: ScriptAlertRequest,
    private val onResult: (Int?) -> Unit
) : BaseDialog(context) {

    private var reported = false

    override fun setupDialog() {
        val binding = DialogScriptAlertBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        binding.rootContainer.background?.alpha = (mmkv.getDialogOpacity() * 255).toInt()

        binding.alertMessage.text = request.message

        val positive = request.positive ?: context.getString(R.string.script_alert_default_positive)
        binding.btnPositive.text = positive
        binding.btnPositive.setOnClickListener { finish(1) }

        request.negative?.let { label ->
            binding.btnNegative.text = label
            binding.btnNegative.visibility = android.view.View.VISIBLE
            binding.btnNegative.setOnClickListener { finish(2) }
        }
        request.neutral?.let { label ->
            binding.btnNeutral.text = label
            binding.btnNeutral.visibility = android.view.View.VISIBLE
            binding.btnNeutral.setOnClickListener { finish(3) }
        }

        // BaseDialog 仅 hook onDismiss(返回/back 触发),用其兜底为取消结果。
        // reported 守卫保证按钮回调已计入后不会再重复 null。
        onDismiss = { finish(null) }
    }

    private fun finish(code: Int?) {
        if (reported) return
        reported = true
        onResult(code)
        dismiss()
    }
}
