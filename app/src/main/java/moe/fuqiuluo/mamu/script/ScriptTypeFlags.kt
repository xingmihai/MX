package moe.fuqiuluo.mamu.script

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

object ScriptTypeFlags {
    const val BYTE = 1
    const val WORD = 2
    const val DWORD = 4
    const val FLOAT = 16
    const val QWORD = 32
    const val DOUBLE = 64

    fun toDisplayType(flags: Int): DisplayValueType? {
        return when (flags) {
            BYTE -> DisplayValueType.BYTE
            WORD -> DisplayValueType.WORD
            DWORD -> DisplayValueType.DWORD
            FLOAT -> DisplayValueType.FLOAT
            QWORD -> DisplayValueType.QWORD
            DOUBLE -> DisplayValueType.DOUBLE
            else -> null
        }
    }

    fun fromDisplayType(type: DisplayValueType?): Int {
        return when (type) {
            DisplayValueType.BYTE -> BYTE
            DisplayValueType.WORD -> WORD
            DisplayValueType.DWORD -> DWORD
            DisplayValueType.FLOAT -> FLOAT
            DisplayValueType.QWORD -> QWORD
            DisplayValueType.DOUBLE -> DOUBLE
            else -> DWORD
        }
    }
}
