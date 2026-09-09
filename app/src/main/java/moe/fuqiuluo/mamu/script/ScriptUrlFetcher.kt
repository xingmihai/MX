package moe.fuqiuluo.mamu.script

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object ScriptUrlFetcher {
    const val MAX_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun validate(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "URL 为空"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "URL 不合法"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "仅支持 http 或 https"
        if (uri.host.isNullOrBlank()) return "URL 不合法"
        return null
    }

    fun fetch(raw: String): String {
        validate(raw)?.let { throw IllegalArgumentException(it) }
        val connection = URL(raw.trim()).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalArgumentException("下载失败: HTTP $code")
            }
            val contentLength = connection.contentLength
            if (contentLength > MAX_BYTES) {
                throw IllegalArgumentException("脚本过大")
            }
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (out.size() + read > MAX_BYTES) {
                        throw IllegalArgumentException("脚本过大")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8).trim()
            if (text.isEmpty()) throw IllegalArgumentException("脚本为空")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
