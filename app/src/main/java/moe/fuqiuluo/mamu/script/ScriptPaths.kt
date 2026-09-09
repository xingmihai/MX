package moe.fuqiuluo.mamu.script

object ScriptPaths {
    const val DEFAULT_DIR = "/sdcard"

    fun normalize(path: String): String {
        val raw = path.trim().ifEmpty { DEFAULT_DIR }
        val absolute = raw.startsWith('/')
        val parts = mutableListOf<String>()
        raw.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) {
            if (joined.isEmpty()) "/" else "/$joined"
        } else {
            joined.ifEmpty { DEFAULT_DIR }
        }
    }

    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == "/") return null
        val index = normalized.lastIndexOf('/')
        if (index <= 0) return "/"
        return normalized.substring(0, index)
    }

    fun child(parent: String, name: String): String {
        val base = normalize(parent).trimEnd('/')
        return if (base == "/") "/$name" else "$base/$name"
    }
}
