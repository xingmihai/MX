package moe.fuqiuluo.mamu.script

object ScriptAddress {
    fun parse(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("0x") || text.startsWith("0X")) {
            if (text.length <= 2) return null
            return text.substring(2).toULongOrNull(16)?.toLong()
        }
        return text.toULongOrNull()?.toLong() ?: text.toLongOrNull()
    }

    fun parseNumber(value: Double): Long {
        return if (value >= 0.0) {
            value.toULong().toLong()
        } else {
            value.toLong()
        }
    }

    fun toHex(address: Long): String {
        return "0x" + java.lang.Long.toUnsignedString(address, 16)
    }
}
