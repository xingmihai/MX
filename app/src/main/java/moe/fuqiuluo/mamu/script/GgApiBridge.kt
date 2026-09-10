package moe.fuqiuluo.mamu.script

import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.PointerChainResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import kotlin.math.abs

class GgApiBridge(
    private val selectedResults: List<ScriptResultItem>,
    private val onToast: (String) -> Unit,
    private val onWarn: (String) -> Unit,
    private val getResults: (Int) -> List<ScriptResultItem>,
    private val isProcessBound: () -> Boolean,
    private val currentPid: () -> Int,
    private val processName: () -> String?,
    private val readMemory: (Long, Int) -> ByteArray?,
    private val writeMemory: (Long, ByteArray) -> Boolean,
    private val onGetResultsCount: () -> Long = { 0L },
    private val onClearResults: () -> Unit = {},
    private val onGetMemoryRanges: (String?) -> List<ScriptMemoryRange> = { emptyList() },
    private val onCopyText: (String) -> Unit = {},
    private val onFreeze: (Long, ByteArray, Int) -> Boolean = { _, _, _ -> false },
    private val onUnfreeze: (Long) -> Boolean = { false },
    // 交互式对话框回调。运行在 worker 线程,需阻塞等待主线程 UI 结果。返回 null 表示用户取消。
    private val onAlert: (ScriptAlertRequest) -> Int? = { null },
    private val onChoice: (ScriptChoiceRequest) -> Int? = { null },
    private val onMultiChoice: (ScriptChoiceRequest) -> List<Int>? = { null },
    private val onPrompt: (ScriptPromptRequest) -> List<String>? = { null },
    // 悬浮窗可见性:get 返回当前可见,set 切换可见状态。
    private val onIsVisible: () -> Boolean = { true },
    private val onSetVisible: (Boolean) -> Unit = {}
) {
    var shouldInterrupt: () -> Boolean = { false }

    fun install(globals: org.luaj.vm2.Globals) {
        val gg = LuaTable()
        gg.set("TYPE_BYTE", ScriptTypeFlags.BYTE)
        gg.set("TYPE_WORD", ScriptTypeFlags.WORD)
        gg.set("TYPE_DWORD", ScriptTypeFlags.DWORD)
        gg.set("TYPE_XOR", ScriptTypeFlags.XOR)
        gg.set("TYPE_FLOAT", ScriptTypeFlags.FLOAT)
        gg.set("TYPE_QWORD", ScriptTypeFlags.QWORD)
        gg.set("TYPE_DOUBLE", ScriptTypeFlags.DOUBLE)
        gg.set("TYPE_AUTO", ScriptTypeFlags.AUTO)
        gg.set("REGION_JAVA_HEAP", 1)
        gg.set("REGION_C_HEAP", 2)
        gg.set("REGION_C_ALLOC", 4)
        gg.set("REGION_C_DATA", 8)
        gg.set("REGION_C_BSS", 16)
        gg.set("REGION_PPSSPP", 32)
        gg.set("REGION_ANONYMOUS", 64)
        gg.set("REGION_JAVA", 128)
        gg.set("REGION_STACK", 256)
        gg.set("REGION_ASHMEM", 512)
        gg.set("REGION_VIDEO", 1024)
        gg.set("REGION_OTHER", 2048)
        gg.set("REGION_BAD", 4096)
        gg.set("REGION_CODE_APP", 8192)
        gg.set("REGION_CODE_SYS", 16384)
        gg.set("getTargetInfo", GetTargetInfo())
        gg.set("getTargetPackage", GetTargetPackage())
        gg.set("readValue", ReadValue())
        gg.set("writeValue", WriteValue())
        gg.set("toast", ToastFn())
        gg.set("sleep", SleepFn())
        gg.set("copyText", CopyTextFn())
        gg.set("getResults", GetResults())
        gg.set("getResultsCount", GetResultsCount())
        gg.set("getResultCount", GetResultsCount())
        gg.set("getSelectedResults", GetSelectedResults())
        gg.set("clearResults", ClearResults())
        gg.set("getValues", GetValues())
        gg.set("setValues", SetValues())
        gg.set("copyMemory", CopyMemory())
        gg.set("getRangesList", GetRangesList())
        gg.set("makeRequest", MakeRequest())
        gg.set("alert", AlertFn())
        gg.set("choice", ChoiceFn())
        gg.set("multiChoice", MultiChoiceFn())
        gg.set("prompt", PromptFn())
        gg.set("isVisible", IsVisibleFn())
        gg.set("setVisible", SetVisibleFn())
        globals.set("gg", gg)
    }

    private inner class GetTargetInfo : ZeroArgFunction() {
        override fun call(): LuaValue {
            if (!isProcessBound()) return NIL
            val table = LuaTable()
            table.set("pid", currentPid())
            table.set("processName", processName() ?: "")
            return table
        }
    }

    private inner class GetTargetPackage : ZeroArgFunction() {
        override fun call(): LuaValue {
            if (!isProcessBound()) return NIL
            return LuaValue.valueOf(processName() ?: "")
        }
    }

    private inner class ReadValue : TwoArgFunction() {
        override fun call(address: LuaValue, type: LuaValue): LuaValue {
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return NIL
            }
            val addr = parseAddress(address) ?: return NIL
            val flags = type.checkint()
            val displayType = resolveReadType(flags)
                ?: throw LuaError("unsupported type flag: $flags")
            val size = displayType.memorySize.toInt()
            if (size <= 0) return NIL
            val bytes = readMemory(addr, size) ?: return NIL
            return LuaValue.valueOf(ValueTypeUtils.bytesToDisplayValue(bytes, displayType))
        }
    }

    private inner class WriteValue : ThreeArgFunction() {
        override fun call(address: LuaValue, value: LuaValue, type: LuaValue): LuaValue {
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return FALSE
            }
            val addr = parseAddress(address) ?: return FALSE
            val flags = type.checkint()
            val baseType = ScriptTypeFlags.toDisplayType(flags)
                ?: throw LuaError("unsupported type flag: $flags")
            val displayType = resolveWriteType(baseType, value)
            val bytes = encodeValue(value, displayType) ?: return FALSE
            return LuaValue.valueOf(writeMemory(addr, bytes))
        }
    }

    private inner class ToastFn : OneArgFunction() {
        override fun call(message: LuaValue): LuaValue {
            onToast(message.tojstring())
            return NONE
        }
    }

    private inner class SleepFn : OneArgFunction() {
        override fun call(ms: LuaValue): LuaValue {
            val duration = if (ms.isnil()) 0L else ms.todouble().toLong().coerceAtLeast(0)
            val startAt = System.currentTimeMillis()
            while (true) {
                throwIfInterrupted()
                // Compute remaining via subtraction instead of start + duration so that
                // durations near Long.MAX_VALUE (e.g. gg.sleep(math.huge)) don't overflow
                // into a past deadline. Clamp elapsed to guard against clock rollback.
                val elapsed = (System.currentTimeMillis() - startAt).coerceAtLeast(0)
                val remain = duration - elapsed
                if (remain <= 0) break
                try {
                    Thread.sleep(minOf(remain, 50L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw LuaError("script interrupted")
                }
            }
            return NONE
        }
    }

    private inner class CopyTextFn : OneArgFunction() {
        override fun call(text: LuaValue): LuaValue {
            onCopyText(text.tojstring())
            return NONE
        }
    }

    private inner class GetResults : OneArgFunction() {
        override fun call(maxCount: LuaValue): LuaValue {
            // Omitting the argument (or passing nil) used to request Int.MAX_VALUE,
            // forcing the JNI bridge to materialize every match and read its value
            // before we build the Lua table. Large result sets can exhaust memory or
            // block the worker long enough to make the app unusable. Impose a safe
            // upper bound instead. 0 is a valid request (returns no results).
            val requested = when {
                maxCount.isnil() -> MAX_GET_RESULTS
                maxCount.isnumber() -> maxCount.todouble().toLong()
                    .coerceIn(0L, MAX_GET_RESULTS.toLong()).toInt()
                else -> maxCount.checkint().coerceIn(0, MAX_GET_RESULTS)
            }
            return toLuaResultTable(getResults(requested))
        }
    }

    private inner class GetResultsCount : ZeroArgFunction() {
        override fun call(): LuaValue {
            return LuaValue.valueOf(onGetResultsCount().toDouble())
        }
    }

    private inner class GetSelectedResults : ZeroArgFunction() {
        override fun call(): LuaValue {
            return toLuaResultTable(selectedResults)
        }
    }

    private inner class ClearResults : ZeroArgFunction() {
        override fun call(): LuaValue {
            onClearResults()
            return NONE
        }
    }

    private inner class GetValues : OneArgFunction() {
        override fun call(items: LuaValue): LuaValue {
            if (!items.istable()) {
                throw LuaError("gg.getValues: expected table")
            }
            if (!isProcessBound()) {
                // 返回空表而不是原表：原表里是上一次的陈旧 value，脚本拿到后会
                // 当作本次刷新的结果继续用，属于静默错误。空表会让后续遍历直接
                // 不执行，问题更早暴露。
                onWarn("未绑定进程，无法读写内存")
                return LuaTable()
            }
            val table = items.checktable()
            for (i in 1..table.length()) {
                throwIfInterrupted()
                val row = table.get(i)
                if (!row.istable()) continue
                val addr = parseAddress(row.get("address")) ?: continue
                val flags = row.get("flags").optint(ScriptTypeFlags.DWORD)
                val displayType = resolveReadType(flags)
                    ?: throw LuaError("unsupported type flag: $flags")
                val size = displayType.memorySize.toInt()
                if (size <= 0) continue
                val bytes = readMemory(addr, size) ?: continue
                row.set("value", ValueTypeUtils.bytesToDisplayValue(bytes, displayType))
            }
            return table
        }
    }

    private inner class SetValues : OneArgFunction() {
        override fun call(items: LuaValue): LuaValue {
            if (!items.istable()) {
                throw LuaError("gg.setValues: expected table")
            }
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return FALSE
            }
            val table = items.checktable()
            var ok = true
            for (i in 1..table.length()) {
                throwIfInterrupted()
                val row = table.get(i)
                if (!row.istable()) {
                    ok = false
                    continue
                }
                val addr = parseAddress(row.get("address"))
                if (addr == null) {
                    ok = false
                    continue
                }
                val flags = row.get("flags").optint(ScriptTypeFlags.DWORD)
                val baseType = ScriptTypeFlags.toDisplayType(flags)
                    ?: throw LuaError("unsupported type flag: $flags")
                val displayType = resolveWriteType(baseType, row.get("value"))
                val bytes = encodeValue(row.get("value"), displayType)
                if (bytes == null) {
                    ok = false
                    continue
                }
                val written = writeMemory(addr, bytes)
                if (!written) {
                    ok = false
                }
                val freezeField = row.get("freeze")
                if (!freezeField.isnil()) {
                    if (freezeField.toboolean()) {
                        if (written) {
                            // Only register the freeze after a successful write, and fold
                            // the callback's result into the returned status so a failed
                            // registration does not let gg.setValues report success.
                            if (!onFreeze(addr, bytes, displayType.nativeId)) {
                                ok = false
                            }
                        } else {
                            // Write failed for a freeze=true request on an address that
                            // may already be frozen with a previous (now stale) value.
                            // Remove the existing entry so the freeze worker stops
                            // hammering the old value even though this batch reports
                            // failure. Fold the result into ok for consistent reporting.
                            if (!onUnfreeze(addr)) {
                                ok = false
                            }
                        }
                    } else {
                        if (!onUnfreeze(addr)) {
                            ok = false
                        }
                    }
                }
            }
            return LuaValue.valueOf(ok)
        }
    }

    private inner class CopyMemory : ThreeArgFunction() {
        override fun call(from: LuaValue, to: LuaValue, bytes: LuaValue): LuaValue {
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return FALSE
            }
            val src = parseAddress(from) ?: return FALSE
            val dst = parseAddress(to) ?: return FALSE
            val size = bytes.checkint()
            if (size <= 0) return FALSE
            if (size > MAX_COPY_BYTES) {
                throw LuaError("gg.copyMemory: size too large")
            }
            // 先把整块源数据读到内存再整段写入，等价于 memmove：即使两段区间重叠，
            // 写入的也是原始源字节，结果确定且正确，因此不需要对重叠发出警告。
            val data = readMemory(src, size) ?: return FALSE
            return LuaValue.valueOf(writeMemory(dst, data))
        }
    }

    private inner class GetRangesList : OneArgFunction() {
        override fun call(filter: LuaValue): LuaValue {
            if (!isProcessBound()) {
                // 返回空表：false 会让 ipairs() 直接抛 "bad argument: table expected"。
                onWarn("未绑定进程，无法读写内存")
                return LuaTable()
            }
            val nameFilter = when {
                filter.isnil() -> null
                filter.isstring() -> filter.tojstring().takeIf { it.isNotBlank() && it != "nil" }
                else -> null
            }
            val table = LuaTable()
            onGetMemoryRanges(nameFilter).forEachIndexed { index, range ->
                val row = LuaTable()
                row.set("start", ScriptAddress.toHex(range.start))
                row.set("end", ScriptAddress.toHex(range.end))
                row.set("name", range.name)
                // GG 脚本按 v.type 过滤内存段（如 'rw-'），此前缺这个字段会让过滤
                // 条件恒为 nil。权限串同时保留在 state 上，兼容两种写法。
                row.set("type", range.state)
                row.set("state", range.state)
                table.set(index + 1, row)
            }
            return table
        }
    }

    private inner class MakeRequest : OneArgFunction() {
        override fun call(url: LuaValue): LuaValue {
            if (url.isnil()) {
                throw LuaError("gg.makeRequest: URL 为空")
            }
            val raw = url.tojstring()
            if (raw.isBlank() || raw == "nil") {
                throw LuaError("gg.makeRequest: URL 为空")
            }
            val result = ScriptUrlFetcher.request(raw)
            if (result.error != null) {
                onWarn(result.error)
            }
            val table = LuaTable()
            table.set("code", result.code)
            table.set("url", result.url)
            table.set("content", result.content)
            // 成功时 error 必须是 nil 而不是 false：GG 脚本用 if r.error then 判定失败，
            // false 虽为假值，但 r.error ~= nil 这类判断会误判成出错。
            if (result.error != null) {
                table.set("error", result.error)
            }
            return table
        }
    }

    // gg.alert(message, [positive, [negative, [neutral]]]) -> 1|2|3|-1
    // 返回: 1=positive, 2=negative, 3=neutral, -1=用户关闭对话框
    private inner class AlertFn : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            val message = args.arg(1).takeIf { it.isstring() }?.tojstring().orEmpty()
            val positive = args.arg(2).takeIf { it.isstring() }?.tojstring()
            val negative = args.arg(3).takeIf { it.isstring() }?.tojstring()
            val neutral = args.arg(4).takeIf { it.isstring() }?.tojstring()
            val result = onAlert(ScriptAlertRequest(message, positive, negative, neutral))
            throwIfInterrupted()
            return LuaValue.valueOf(result ?: -1)
        }
    }

    // gg.choice(items, [selected, [message]]) -> index|nil
    // items: 字符串数组; selected: 预选索引(1-based)或 nil; message: 标题
    // 返回: 选中的 1-based 索引,用户取消返回 nil
    private inner class ChoiceFn : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            val itemsTable = args.arg(1).takeIf { it.istable() }
            if (itemsTable == null) return NIL
            val items = (1..itemsTable.length()).mapNotNull { i ->
                itemsTable.get(i).takeIf { it.isstring() }?.tojstring()
            }
            val selectedArg = args.arg(2).takeIf { it.isint() }?.toint()
            val selected = selectedArg?.let { if (it in 1..items.size) it else null }
            val message = args.arg(3).takeIf { it.isstring() }?.tojstring()
            val result = onChoice(ScriptChoiceRequest(items, selected, message))
            throwIfInterrupted()
            return result?.let { LuaValue.valueOf(it) } ?: NIL
        }
    }

    // gg.multiChoice(items, [selected, [message]]) -> table|nil
    // 返回: 仅含被选中项的索引->true 的表,用户取消返回 nil
    private inner class MultiChoiceFn : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            val itemsTable = args.arg(1).takeIf { it.istable() }
            if (itemsTable == null) return NIL
            val items = (1..itemsTable.length()).mapNotNull { i ->
                itemsTable.get(i).takeIf { it.isstring() }?.tojstring()
            }
            // 解析 arg2 布尔表为预选(勾选)索引集合(1-based)。
            // 早期版本丢弃该参数,导致 multiChoice 无法显示初始勾选状态。
            val selectedArg = args.arg(2).takeIf { it.istable() }
            val preselected = selectedArg?.let { tbl ->
                (1..items.size).filter { i -> tbl.get(i).toboolean() }.toSet()
            } ?: emptySet()
            val message = args.arg(3).takeIf { it.isstring() }?.tojstring()
            val result = onMultiChoice(ScriptChoiceRequest(items, null, message, preselected))
            throwIfInterrupted()
            if (result == null) return NIL
            val out = LuaTable()
            result.forEach { idx -> if (idx in 1..items.size) out.set(idx, LuaValue.TRUE) }
            return out
        }
    }

    // gg.prompt(labels, [defaults, [types]]) -> table|nil
    // labels: 字符串数组; defaults: 字符串/数字数组; types: "text"|"number"|"decimal"
    // 返回: 索引->字符串值的表,用户取消返回 nil
    private inner class PromptFn : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            val labelsTable = args.arg(1).takeIf { it.istable() }
            if (labelsTable == null) return NIL
            val labels = (1..labelsTable.length()).mapNotNull { i ->
                labelsTable.get(i).takeIf { it.isstring() }?.tojstring()
            }
            val defaultsTable = args.arg(2).takeIf { it.istable() }
            val defaults = (1..labels.size).map { i ->
                defaultsTable?.get(i)?.takeIf { it.isstring() || it.isnumber() }?.tojstring().orEmpty()
            }
            val typesTable = args.arg(3).takeIf { it.istable() }
            val types = (1..labels.size).map { i ->
                typesTable?.get(i)?.takeIf { it.isstring() }?.tojstring() ?: "text"
            }
            val result = onPrompt(ScriptPromptRequest(labels, defaults, types))
            throwIfInterrupted()
            if (result == null) return NIL
            val out = LuaTable()
            result.forEachIndexed { index, value ->
                if (index < labels.size) out.set(index + 1, LuaValue.valueOf(value))
            }
            return out
        }
    }

    private fun throwIfInterrupted() {
        if (shouldInterrupt()) {
            throw LuaError("script interrupted")
        }
    }

    /**
     * 读取内存时的类型解析。AUTO 不含宽度信息，按 Dword 兜底并给出提示。
     */
    private fun resolveReadType(flags: Int): DisplayValueType? {
        val type = ScriptTypeFlags.toReadDisplayType(flags) ?: return null
        if (type != ScriptTypeFlags.toDisplayType(flags)) {
            onWarn("gg: TYPE_AUTO 无法确定读取宽度，本次按 Dword 处理")
        }
        return type
    }

    /**
     * 写入内存时的类型解析。AUTO 会按待写入的值推断出具体类型。
     */
    private fun resolveWriteType(baseType: DisplayValueType, value: LuaValue): DisplayValueType {
        if (baseType != DisplayValueType.AUTO) return baseType
        // 数字必须走 Double 重载：先转字符串会把 5.7 截断成 "5"，
        // 导致 AUTO 推断成 Dword 并按整数写入，丢掉小数部分。
        if (value.isnumber()) return ValueTypeUtils.inferAutoType(value.todouble())
        return ValueTypeUtils.inferAutoType(
            runCatching { value.tojstring() }.getOrDefault("")
        )
    }

    /**
     * 把 Lua number 转成整数字符串，供后续按目标宽度解析。
     *
     * Kotlin 的 Double.toLong() / toULong() 在越界时是饱和而不是报错，
     * 直接调用会把越界值静默变成 Long.MAX_VALUE / ULong 边界值写进内存。
     * 这里先做区间判断：
     *   - [-2^63, 2^63)  → 按有符号输出
     *   - [2^63, 2^64)   → 按无符号输出（Qword 的合法上半区）
     *   - 其余           → 返回 null，由调用方决定报错还是改用其它类型
     *
     * 带小数部分的值仍按截断输出，交给 parseExprToBytes 做宽度校验。
     */
    private fun integerStringOrNull(d: Double): String? {
        if (!d.isFinite()) return null
        if (d % 1.0 != 0.0) return d.toLong().toString()
        return when {
            d < -POW_2_63 -> null
            d < POW_2_63 -> d.toLong().toString()
            d < POW_2_64 -> d.toULong().toString()
            else -> null
        }
    }

    private fun parseAddress(value: LuaValue): Long? {
        return when {
            value.isstring() -> ScriptAddress.parse(value.tojstring())
            value.isnumber() -> ScriptAddress.parseNumber(value.todouble())
            else -> null
        }
    }

    private fun encodeValue(value: LuaValue, displayType: DisplayValueType): ByteArray? {
        val raw = when {
            value.isnil() -> return null
            value.isnumber() && (displayType == DisplayValueType.FLOAT ||
                displayType == DisplayValueType.DOUBLE) ->
                value.todouble().toString()
            value.isnumber() -> {
                val d = value.todouble()
                // Lua number 是 double，只有 53 位有效位。超过 2^53 的 Qword 传数字会
                // 静默丢精度（写进去的是另一个数），必须提示改用字符串传值。
                if (displayType == DisplayValueType.QWORD && abs(d) >= MAX_SAFE_INTEGER) {
                    onWarn("gg: Qword 值超过 2^53，Lua number 无法精确表示，请改用字符串传值")
                }
                val asInteger = integerStringOrNull(d)
                if (asInteger == null) {
                    // 越界时不能沿用 toLong() 的饱和结果，否则会把
                    // Long.MAX_VALUE 当成请求值写进内存。
                    onWarn("gg: 数值超出 64 位整数范围，无法写入")
                    return null
                }
                asInteger
            }
            // 原先的 checkjstring() 会抛 LuaError，和本类其它地方"转换失败返回 null
            // → 记为失败并跳过"的容错风格不一致：坏一行会中断整个脚本。
            else -> runCatching { value.tojstring() }.getOrNull() ?: return null
        }
        return try {
            ValueTypeUtils.parseExprToBytes(raw, displayType)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_COPY_BYTES = 1024 * 1024
        private const val MAX_GET_RESULTS = 100_000
        // 2^53：double 能精确表示的最大整数。
        private const val MAX_SAFE_INTEGER = 9007199254740992.0
        // 64 位整数的有符号 / 无符号区间端点，用于避免 toLong()/toULong() 的饱和行为。
        private const val POW_2_63 = 9223372036854775808.0
        private const val POW_2_64 = 18446744073709551616.0

        fun clampResultLimit(requested: Int, total: Long): Int {
            if (requested <= 0 || total <= 0L) return 0
            return minOf(requested.toLong(), total, Int.MAX_VALUE.toLong()).toInt()
        }

        fun SearchResultItem.toScriptResultItem(): ScriptResultItem {
            val (address, value) = when (this) {
                is ExactSearchResultItem -> address to this.value
                is FuzzySearchResultItem -> address to this.value
                is PointerChainResultItem -> address to this.value
                else -> 0L to ""
            }
            return ScriptResultItem(
                address = address,
                value = value,
                flags = ScriptTypeFlags.fromDisplayType(displayValueType)
            )
        }

        fun toLuaResultTable(items: List<ScriptResultItem>): LuaTable {
            val table = LuaTable()
            items.forEachIndexed { index, item ->
                val row = LuaTable()
                row.set("address", ScriptAddress.toHex(item.address))
                row.set("value", item.value)
                row.set("flags", item.flags)
                table.set(index + 1, row)
            }
            return table
        }
    }

    private inner class IsVisibleFn : OneArgFunction() {
        // 返回悬浮窗是否可见。arg 为 true 时忽略其他覆盖层精确检测(与 GG 兼容)。
        override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(onIsVisible())
    }

    private inner class SetVisibleFn : OneArgFunction() {
        // 设置悬浮窗可见/隐藏,参数为 true/false。
        override fun call(arg: LuaValue): LuaValue {
            onSetVisible(arg.toboolean())
            return NONE
        }
    }
}
