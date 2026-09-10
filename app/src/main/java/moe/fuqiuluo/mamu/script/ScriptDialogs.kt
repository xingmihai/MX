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
    val message: String?,
    /**
     * 预选(勾选)项的 1-based 索引集合,主要用于 gg.multiChoice 的布尔表参数。
     * gg.choice 单选时也可在此填预选项,但通常只用 [selected]。
     */
    val preselected: Set<Int> = emptySet()
)

data class ScriptPromptRequest(
    val labels: List<String>,
    val defaults: List<String>,
    val types: List<String>
)
