package moe.fuqiuluo.mamu.script

/**
 * 与脚本交互的 UI 回调契约。
 *
 * 这些回调运行在 Lua worker 线程上，必须在主线程展示 UI、阻塞 worker 等待用户输入，
 * 然后返回结果。回调返回 null 表示用户取消（脚本侧会返回 nil）。
 */
data class ScriptAlertRequest(
    val message: String,
    val positive: String?,
    val negative: String?,
    val neutral: String?
)

data class ScriptChoiceRequest(
    val items: List<String>,
    val selected: Int?,
    val message: String?
)

data class ScriptPromptRequest(
    val labels: List<String>,
    val defaults: List<String>,
    val types: List<String>
)
