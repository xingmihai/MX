package moe.fuqiuluo.mamu.script

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

object ScriptTypeFlags {
    const val BYTE = 1
    const val WORD = 2
    const val DWORD = 4
    const val XOR = 8
    const val FLOAT = 16
    const val QWORD = 32
    const val DOUBLE = 64
    // 127 是 GameGuardian 里 TYPE_AUTO 的值。此前未导出，脚本取到的 gg.TYPE_AUTO 是 nil，
    // 传给 type.checkint() 会直接抛 "bad argument: number expected, got nil"。
    const val AUTO = 127

    fun toDisplayType(flags: Int): DisplayValueType? {
        return when (flags) {
            BYTE -> DisplayValueType.BYTE
            WORD -> DisplayValueType.WORD
            DWORD -> DisplayValueType.DWORD
            XOR -> DisplayValueType.XOR
            FLOAT -> DisplayValueType.FLOAT
            QWORD -> DisplayValueType.QWORD
            DOUBLE -> DisplayValueType.DOUBLE
            AUTO -> DisplayValueType.AUTO
            else -> null
        }
    }

    /**
     * 读取内存时使用的类型。AUTO 不含宽度信息、无法据此决定读几个字节，
     * 按 Dword 兜底（与 fromDisplayType 的默认分支一致）。
     */
    fun toReadDisplayType(flags: Int): DisplayValueType? {
        val type = toDisplayType(flags) ?: return null
        return if (type == DisplayValueType.AUTO) DisplayValueType.DWORD else type
    }

    fun fromDisplayType(type: DisplayValueType?): Int {
        return when (type) {
            DisplayValueType.BYTE -> BYTE
            DisplayValueType.WORD -> WORD
            DisplayValueType.DWORD -> DWORD
            DisplayValueType.XOR -> XOR
            DisplayValueType.FLOAT -> FLOAT
            DisplayValueType.QWORD -> QWORD
            DisplayValueType.DOUBLE -> DOUBLE
            DisplayValueType.AUTO -> AUTO
            else -> DWORD
        }
    }
}
