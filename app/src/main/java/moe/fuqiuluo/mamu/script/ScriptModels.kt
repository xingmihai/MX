package moe.fuqiuluo.mamu.script

data class ScriptResultItem(
    val address: Long,
    val value: String,
    val flags: Int
)

data class ScriptMemoryRange(
    val start: Long,
    val end: Long,
    val name: String,
    val state: String
)

/**
 * 异步搜索的轮询结果。
 *
 * @param finished 搜索是否已结束（完成、取消或出错）
 * @param count 已找到的结果数；未结束时是中间值，结束时是最终总数
 * @param error 非空表示搜索失败或已取消
 */
data class ScriptSearchStatus(
    val finished: Boolean,
    val count: Long,
    val error: String?
)

sealed class ScriptEndReason {
    data object Completed : ScriptEndReason()
    data object Stopped : ScriptEndReason()
    data object Timeout : ScriptEndReason()
    data class Error(val message: String, val line: Int?) : ScriptEndReason()
}
