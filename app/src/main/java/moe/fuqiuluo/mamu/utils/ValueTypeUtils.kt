package moe.fuqiuluo.mamu.utils

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.text.HexFormat

object ValueTypeUtils {
    private val hexFormat = HexFormat { upperCase = true }
    /**
     * 推断 gg.TYPE_AUTO 写入时应使用的具体类型。
     *
     * AUTO 不携带宽度信息，只有拿到具体的值才能判断。规则与 GameGuardian 一致：
     * 整数按数值范围落进 Dword / Qword，小数按是否落在 float 范围内落进 Float / Double。
     *
     * @param raw 待写入值的字符串形式
     * @return 推断出的具体类型（永不为 AUTO）
     */
    fun inferAutoType(raw: String): DisplayValueType {
        val text = raw.trim()
        val isHex = text.startsWith("0x", ignoreCase = true) ||
            text.startsWith("-0x", ignoreCase = true)
        // 注意先判十六进制：0xE 这类字面量含 'e'，不能当成科学计数法。
        val looksFloat = !isHex &&
            (text.contains('.') || text.contains('e', ignoreCase = true))

        if (looksFloat) {
            val value = text.toDoubleOrNull()
            val fitsFloat = value != null &&
                !value.isNaN() &&
                value.isFinite() &&
                value >= -Float.MAX_VALUE.toDouble() &&
                value <= Float.MAX_VALUE.toDouble()
            return if (fitsFloat) DisplayValueType.FLOAT else DisplayValueType.DOUBLE
        }

        val asLong = text.toLongOrNull()
            ?: text.toULongOrNull()?.toLong()
            ?: return DisplayValueType.DWORD
        return if (asLong in Int.MIN_VALUE.toLong()..0xFFFFFFFFL) {
            DisplayValueType.DWORD
        } else {
            DisplayValueType.QWORD
        }
    }

    /**
     * Parse expression string to byte array based on value type
     * @param expr Input expression string
     * @param valueType Target value type for conversion
     * @return Byte array representation in little-endian format
     */
    fun parseExprToBytes(expr: String, valueType: DisplayValueType): ByteArray {
        val formattedExpr = expr.trim()

        when (valueType) {
            DisplayValueType.AUTO -> {
                throw IllegalArgumentException("AUTO type should not be used for value conversion")
            }

            DisplayValueType.BYTE -> {
                val value = formattedExpr.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid byte value: $formattedExpr")
                when {
                    value in -128L..255L -> return byteArrayOf(value.toByte())
                    else -> throw IllegalArgumentException("Byte value out of range (-128~255): $value")
                }
            }

            DisplayValueType.WORD -> {
                val value = formattedExpr.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid word value: $formattedExpr")

                val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                when (value) {
                    in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() ->
                        buffer.putShort(value.toShort())

                    in 0L..0xFFFFL ->
                        buffer.putShort(value.toShort()) // Unsigned range, cast to signed

                    else -> throw IllegalArgumentException("Word value out of range (-32768~65535): $value")
                }
                return buffer.array()
            }

            DisplayValueType.DWORD -> {
                val value = formattedExpr.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid dword value: $formattedExpr")

                val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                when (value) {
                    in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ->
                        buffer.putInt(value.toInt())

                    in 0L..0xFFFFFFFFL ->
                        buffer.putInt(value.toInt()) // Unsigned range, cast to signed

                    else -> throw IllegalArgumentException("Dword value out of range (-2147483648~4294967295): $value")
                }
                return buffer.array()
            }

            DisplayValueType.QWORD -> {
                val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    // Try signed first (covers -9223372036854775808 ~ 9223372036854775807)
                    buffer.putLong(formattedExpr.toLong())
                } catch (e: NumberFormatException) {
                    // Fallback to unsigned (covers 0 ~ 18446744073709551615)
                    try {
                        buffer.putLong(formattedExpr.toULong().toLong()) // Unsigned to signed cast
                    } catch (e2: NumberFormatException) {
                        throw IllegalArgumentException("Invalid qword value: $formattedExpr", e2)
                    }
                }
                return buffer.array()
            }

            DisplayValueType.FLOAT -> {
                val value = formattedExpr.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid float value: $formattedExpr")
                return ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(value)
                    .array()
            }

            DisplayValueType.DOUBLE -> {
                val value = formattedExpr.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid double value: $formattedExpr")
                return ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putDouble(value)
                    .array()
            }

            DisplayValueType.XOR -> {
                // XOR type uses same format as DWORD but indicates XOR encryption context
                val value = formattedExpr.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid xor value: $formattedExpr")

                val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                when (value) {
                    in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ->
                        buffer.putInt(value.toInt())

                    in 0L..0xFFFFFFFFL ->
                        buffer.putInt(value.toInt()) // Unsigned range, cast to signed

                    else -> throw IllegalArgumentException("Xor value out of range (-2147483648~4294967295): $value")
                }
                return buffer.array()
            }

            DisplayValueType.UTF_8 -> {
                return formattedExpr.toByteArray(Charsets.UTF_8)
            }

            DisplayValueType.UTF_16LE -> {
                return formattedExpr.toByteArray(Charsets.UTF_16LE)
            }

            DisplayValueType.HEX -> {
                TODO("HEX")
            }

            DisplayValueType.HEX_MIXED -> {
                TODO("HEX_MIXED")
            }

            DisplayValueType.ARM, DisplayValueType.ARM64 -> {
                TODO("ARM64")
            }

            DisplayValueType.PATTERN -> {
                // Pattern 类型支持十六进制输入，格式: "1A 2B 3C" 或 "1A2B3C"
                val hexStr = formattedExpr.replace(" ", "").uppercase()
                if (hexStr.length % 2 != 0) {
                    throw IllegalArgumentException("Invalid hex string length: must be even")
                }
                if (!hexStr.all { it in '0'..'9' || it in 'A'..'F' }) {
                    throw IllegalArgumentException("Invalid hex characters in pattern")
                }
                return hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
        }
    }

    /**
     * Convert byte array to display value string based on value type
     * @param bytes Byte array in little-endian format
     * @param valueType Target value type for display
     * @return String representation of the value
     */
    fun bytesToDisplayValue(bytes: ByteArray, valueType: DisplayValueType): String {
        if (bytes.isEmpty()) return ""

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        return when (valueType) {
            DisplayValueType.BYTE -> {
                if (bytes.isNotEmpty()) {
                    bytes[0].toInt().and(0xFF).toString()
                } else ""
            }

            DisplayValueType.WORD -> {
                if (bytes.size >= 2) {
                    buffer.short.toInt().and(0xFFFF).toString()
                } else ""
            }

            DisplayValueType.DWORD, DisplayValueType.XOR -> {
                if (bytes.size >= 4) {
                    buffer.int.toString()
                } else ""
            }

            DisplayValueType.QWORD -> {
                if (bytes.size >= 8) {
                    buffer.long.toString()
                } else ""
            }

            DisplayValueType.FLOAT -> {
                if (bytes.size >= 4) {
                    // 9 位有效数字是 float 无损往返所需的最小值。
                    // 用 6 位会把 3.1415927f 显示成 "3.14159"，脚本 getValues -> setValues
                    // 回写后变成另一个值。格式化固定 Locale.US，避免某些语言环境下
                    // 小数点被输出成逗号。
                    "%.9g".format(Locale.US, buffer.float)
                } else ""
            }

            DisplayValueType.DOUBLE -> {
                if (bytes.size >= 8) {
                    // 17 位是 double 无损往返所需的最小值。
                    "%.17g".format(Locale.US, buffer.double)
                } else ""
            }

            DisplayValueType.UTF_8 -> {
                String(bytes, Charsets.UTF_8)
            }

            DisplayValueType.UTF_16LE -> {
                String(bytes, Charsets.UTF_16LE)
            }

            DisplayValueType.HEX -> {
                bytes.toHexString(hexFormat)
            }

            DisplayValueType.AUTO, DisplayValueType.HEX_MIXED,
            DisplayValueType.ARM, DisplayValueType.ARM64 -> {
                bytes.toHexString(hexFormat)
            }

            DisplayValueType.PATTERN -> {
                // Pattern type displays as hex
                bytes.toHexString(hexFormat)
            }
        }
    }
}