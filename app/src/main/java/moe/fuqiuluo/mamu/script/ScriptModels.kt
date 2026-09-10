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

sealed class ScriptEndReason {
    data object Completed : ScriptEndReason()
    data object Stopped : ScriptEndReason()
    data object Timeout : ScriptEndReason()
    data class Error(val message: String, val line: Int?) : ScriptEndReason()
}
