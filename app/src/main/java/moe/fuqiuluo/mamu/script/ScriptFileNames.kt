package moe.fuqiuluo.mamu.script

object ScriptFileNames {
    private val validName = Regex("^[A-Za-z0-9._-]+\\.lua$", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        if (trimmed.contains("..")) return null
        val withExt = if (trimmed.endsWith(".lua", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed.lua"
        }
        if (!validName.matches(withExt)) return null
        return withExt
    }
}
