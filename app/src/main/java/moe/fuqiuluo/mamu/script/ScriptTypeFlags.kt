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

/**
 * GameGuardian 的 gg.REGION_* 内存区域标志位。
 *
 * 这些值必须与 GG 官方一致，脚本里写死的通常是数值或 gg.REGION_* 常量，
 * 位值错了会让 gg.setRanges 选中完全不相干的区域。
 * 注意它们不是连续的 2^n 序列：OTHER 是负值，CODE_APP 从 16384 起跳。
 */
object ScriptRegions {
    const val C_HEAP = 1            // Ch
    const val JAVA_HEAP = 2         // Jh
    const val C_ALLOC = 4           // Ca
    const val C_DATA = 8            // Cd
    const val C_BSS = 16            // Cb
    const val ANONYMOUS = 32        // An
    const val STACK = 64            // S
    const val CODE_APP = 16384      // Xa
    const val CODE_SYS = 32768      // Xs
    const val BAD = 131072          // B
    const val JAVA = 65536          // J
    const val PPSSPP = 262144       // Ps
    const val ASHMEM = 524288       // As
    const val VIDEO = 1048576       // V
    const val OTHER = -2080896      // O

    /**
     * 把 GG 区域位掩码翻译成本项目 MemoryRange 的 code 集合。
     * 返回空集合表示没有任何已知位被置上。
     */
    fun toRangeCodes(flags: Int): Set<String> {
        val codes = LinkedHashSet<String>()
        fun add(bit: Int, code: String) {
            if ((flags and bit) == bit) codes.add(code)
        }
        add(C_HEAP, "Ch")
        add(JAVA_HEAP, "Jh")
        add(C_ALLOC, "Ca")
        add(C_DATA, "Cd")
        add(C_BSS, "Cb")
        add(ANONYMOUS, "An")
        add(STACK, "S")
        add(CODE_APP, "Xa")
        add(CODE_SYS, "Xs")
        add(BAD, "B")
        add(JAVA, "J")
        add(PPSSPP, "Ps")
        add(ASHMEM, "As")
        add(VIDEO, "V")
        add(OTHER, "O")
        return codes
    }
}
