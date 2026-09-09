package moe.fuqiuluo.mamu.script

import android.content.Context
import com.tencent.mmkv.MMKV
import java.io.File

class ScriptRepository(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    companion object {
        const val DRAFT_KEY = "script_editor_draft"
        private const val DIR_NAME = "scripts"
    }

    private val scriptsDir: File
        get() = File(filesDir, DIR_NAME).also { it.mkdirs() }

    fun list(): List<String> {
        val dir = scriptsDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".lua", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun save(name: String, content: String): String {
        val sanitized = ScriptFileNames.sanitize(name)
            ?: throw IllegalArgumentException("invalid file name")
        File(scriptsDir, sanitized).writeText(content)
        return sanitized
    }

    fun load(name: String): String {
        val sanitized = ScriptFileNames.sanitize(name)
            ?: throw IllegalArgumentException("invalid file name")
        val file = File(scriptsDir, sanitized)
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("script not found")
        }
        return file.readText()
    }

    fun loadDraft(): String {
        return MMKV.defaultMMKV().decodeString(DRAFT_KEY, "") ?: ""
    }

    fun saveDraft(content: String) {
        MMKV.defaultMMKV().encode(DRAFT_KEY, content)
    }
}
