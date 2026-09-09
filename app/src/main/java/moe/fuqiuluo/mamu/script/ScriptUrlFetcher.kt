package moe.fuqiuluo.mamu.script

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ScriptHttpResult(
    val code: Int,
    val url: String,
    val content: String,
    val error: String?
)

object ScriptUrlFetcher {
    const val MAX_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"

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
        val result = request(raw)
        if (result.error != null) {
            throw IllegalArgumentException(result.error)
        }
        if (result.content.isBlank()) {
            throw IllegalArgumentException("脚本为空")
        }
        return result.content
    }

    fun request(raw: String): ScriptHttpResult {
        val url = raw.trim()
        validate(url)?.let { return ScriptHttpResult(0, url, "", it) }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (out.size() + read > MAX_BYTES) {
                        return ScriptHttpResult(code, url, "", "脚本过大")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val text = bytes.toString(Charsets.UTF_8)
            if (code !in 200..299) {
                return ScriptHttpResult(code, url, text, "下载失败: HTTP $code")
            }
            return ScriptHttpResult(code, url, text, null)
        } catch (error: Exception) {
            return ScriptHttpResult(0, url, "", error.message ?: "下载失败")
        } finally {
            connection.disconnect()
        }
    }
}
