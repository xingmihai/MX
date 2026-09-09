package moe.fuqiuluo.mamu.script

import moe.fuqiuluo.mamu.data.local.RootFileSystem
import java.io.File

data class ScriptFsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

object ScriptLocalBrowser {
    fun list(path: String): List<ScriptFsEntry> {
        val dir = ScriptPaths.normalize(path)
        val entries = if (RootFileSystem.isConnected()) {
            RootFileSystem.listFiles(dir).mapNotNull { file ->
                when {
                    file.isDirectory -> ScriptFsEntry(
                        name = file.name,
                        path = ScriptPaths.child(dir, file.name),
                        isDirectory = true
                    )
                    file.isFile && file.name.endsWith(".lua", ignoreCase = true) -> ScriptFsEntry(
                        name = file.name,
                        path = ScriptPaths.child(dir, file.name),
                        isDirectory = false,
                        size = file.length()
                    )
                    else -> null
                }
            }
        } else {
            val file = File(dir)
            if (!file.exists() || !file.isDirectory) {
                throw IllegalArgumentException("目录不存在")
            }
            file.listFiles().orEmpty().mapNotNull { child ->
                when {
                    child.isDirectory -> ScriptFsEntry(
                        name = child.name,
                        path = child.absolutePath,
                        isDirectory = true
                    )
                    child.isFile && child.name.endsWith(".lua", ignoreCase = true) -> ScriptFsEntry(
                        name = child.name,
                        path = child.absolutePath,
                        isDirectory = false,
                        size = child.length()
                    )
                    else -> null
                }
            }
        }
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun read(path: String): String {
        val normalized = ScriptPaths.normalize(path)
        if (!normalized.endsWith(".lua", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持 .lua 文件")
        }
        val content = if (RootFileSystem.isConnected()) {
            RootFileSystem.readText(normalized)
                ?: throw IllegalArgumentException("读取失败")
        } else {
            val file = File(normalized)
            if (!file.exists() || !file.isFile) {
                throw IllegalArgumentException("文件不存在")
            }
            file.readText(Charsets.UTF_8)
        }
        if (content.isBlank()) throw IllegalArgumentException("脚本为空")
        return content
    }
}
