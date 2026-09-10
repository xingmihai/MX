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
    private val onSetVisible: (Boolean) -> Unit = {},
    // 搜索相关。搜索是异步的:宿主启动搜索后立即返回,由桥接层轮询状态,
    // 期间持续响应 shouldInterrupt,避免脚本停止时搜索线程还在跑。
    private val onStartSearch: (String, DisplayValueType) -> Boolean = { _, _ -> false },
    private val onStartRefine: (String, DisplayValueType) -> Boolean = { _, _ -> false },
    private val onSearchStatus: () -> ScriptSearchStatus = {
        ScriptSearchStatus(true, 0L, "not supported")
    },
    private val onCancelSearch: () -> Unit = {},
    private val onSetRanges: (Int) -> Boolean = { false },
    private val onGetRanges: () -> Int = { 0 },
    // 把同一段字节写入当前结果列表的每一项，返回成功条数。
    // 单独成回调而不复用 getResults：getResults 受 MAX_GET_RESULTS 限制且只取
    // 第一页，gg.editAll 承诺改写"全部"结果，必须走分页路径。
    // 第二个参数是中断检查，宿主必须在循环里调用它，否则用户 Stop 后
    // 写入仍会继续（宽搜索的 editAll 可能持续很久）。
    private val onEditAll: (ByteArray, () -> Boolean) -> Int = { _, _ -> 0 }
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
        gg.set("REGION_JAVA_HEAP", ScriptRegions.JAVA_HEAP)
        gg.set("REGION_C_HEAP", ScriptRegions.C_HEAP)
        gg.set("REGION_C_ALLOC", ScriptRegions.C_ALLOC)
        gg.set("REGION_C_DATA", ScriptRegions.C_DATA)
        gg.set("REGION_C_BSS", ScriptRegions.C_BSS)
        gg.set("REGION_PPSSPP", ScriptRegions.PPSSPP)
        gg.set("REGION_ANONYMOUS", ScriptRegions.ANONYMOUS)
        gg.set("REGION_JAVA", ScriptRegions.JAVA)
        gg.set("REGION_STACK", ScriptRegions.STACK)
        gg.set("REGION_ASHMEM", ScriptRegions.ASHMEM)
        gg.set("REGION_VIDEO", ScriptRegions.VIDEO)
        gg.set("REGION_OTHER", ScriptRegions.OTHER)
        gg.set("REGION_BAD", ScriptRegions.BAD)
        gg.set("REGION_CODE_APP", ScriptRegions.CODE_APP)
        gg.set("REGION_CODE_SYS", ScriptRegions.CODE_SYS)
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
        gg.set("setRanges", SetRanges())
        gg.set("getRanges", GetRanges())
        gg.set("searchNumber", SearchNumber())
        gg.set("refineNumber", RefineNumber())
        gg.set("editAll", EditAll())
        globals.set("gg", gg)
    }

    private inner class GetTargetInfo : ZeroArgFunction() {
        override fun call(): LuaValue {
            if (!isProcessBound()) return NIL
            val procName = processName() ?: ""
            val table = LuaTable()
            table.set("pid", currentPid())
            table.set("processName", procName)
            // GG 脚本最常读的是 packageName（例如校验版本），此前完全没有这个字段。
            table.set("packageName", packageNameOf(procName))
            table.set("name", procName)
            return table
        }
    }

    private inner class GetTargetPackage : ZeroArgFunction() {
        override fun call(): LuaValue {
            if (!isProcessBound()) return NIL
            // 返回包名而不是进程名：带 :subprocess 后缀的进程中两者并不相同。
            return LuaValue.valueOf(packageNameOf(processName() ?: ""))
        }
    }

    /**
     * 从进程名推导包名。Android 上主进程的进程名等于包名，
     * 子进程形如 "com.example.game:service"，去掉冒号后缀即为包名。
     */
    private fun packageNameOf(processName: String): String {
        return processName.substringBefore(':')
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
                val text = ValueTypeUtils.bytesToDisplayValue(bytes, displayType)
                row.set("value", toLuaValue(text, displayType))
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
        // 同样按 type() 分派，避免数字字符串被当成数字取 todouble()。
        return when (value.type()) {
            LuaValue.TNUMBER -> ValueTypeUtils.inferAutoType(value.todouble())
            else -> ValueTypeUtils.inferAutoType(
                runCatching { value.tojstring() }.getOrDefault("")
            )
        }
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

    /**
     * 解析地址。必须用 [LuaValue.type] 而不是 isstring()/isnumber() 分派：
     * LuaJ 里 LuaNumber.isstring() 恒为 true、LuaString.isnumber() 对数字字符串
     * 也为 true，两者都会命中错误的分支。数字若走 tojstring()，
     * LuaDouble 会先降为 float 再输出，高精度地址会被悄悄改掉。
     */
    private fun parseAddress(value: LuaValue): Long? {
        return when (value.type()) {
            LuaValue.TNUMBER -> ScriptAddress.parseNumber(value.todouble())
            LuaValue.TSTRING -> ScriptAddress.parse(value.tojstring())
            else -> null
        }
    }

    private fun encodeValue(value: LuaValue, displayType: DisplayValueType): ByteArray? {
        val raw = when {
            value.isnil() -> return null
            // 一律用 type() 判定：LuaJ 的 LuaString.isnumber() 对 "123" 这类
            // 数字字符串也返回 true，若走数字分支会先 todouble() 再转回来，
            // Qword 大数（如 "18446744073709551615"）会被压成
            // 1.8446744073709552E19 而写错值。字符串保持原样交给宽度校验。
            value.type() == LuaValue.TNUMBER && (displayType == DisplayValueType.FLOAT ||
                displayType == DisplayValueType.DOUBLE) ->
                value.todouble().toString()
            value.type() == LuaValue.TNUMBER -> {
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
        // 异步搜索的轮询间隔(毫秒)。
        private const val POLL_INTERVAL_MS = 50L

        /**
         * 把显示值转成 Lua 值。
         *
         * GameGuardian 里结果项的 value 是 number，脚本会直接做比较和算术
         * （if r[1].value > 100 then）。此前一律返回 string，Lua 在算术运算中
         * 虽能隐式转换，但比较运算不会——"9" > "100" 为真，结果完全错误。
         *
         * Qword 超过 double 的 53 位精度时仍返回字符串，避免静默失真；
         * 非数值类型（UTF-8、HEX 等）也保持字符串。
         */
        fun toLuaValue(text: String, type: DisplayValueType?): LuaValue {
            if (type == null) return LuaValue.valueOf(text)
            val asNumber = when (type) {
                DisplayValueType.BYTE, DisplayValueType.WORD, DisplayValueType.DWORD,
                DisplayValueType.XOR, DisplayValueType.FLOAT, DisplayValueType.DOUBLE,
                DisplayValueType.QWORD -> text.toDoubleOrNull()
                else -> null
            } ?: return LuaValue.valueOf(text)
            if (type == DisplayValueType.QWORD && abs(asNumber) >= MAX_SAFE_INTEGER) {
                return LuaValue.valueOf(text)
            }
            return LuaValue.valueOf(asNumber)
        }

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
                row.set("value", toLuaValue(item.value, ScriptTypeFlags.toReadDisplayType(item.flags)))
                row.set("flags", item.flags)
                table.set(index + 1, row)
            }
            return table
        }
    }

    // gg.setRanges(flags) -> boolean
    private inner class SetRanges : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val flags = arg.checkint()
            if (!onSetRanges(flags)) {
                onWarn("gg.setRanges: 未能识别任何内存区域，请检查 flags")
                return FALSE
            }
            return TRUE
        }
    }

    // gg.getRanges() -> flags
    private inner class GetRanges : ZeroArgFunction() {
        override fun call(): LuaValue {
            return LuaValue.valueOf(onGetRanges())
        }
    }

    // gg.searchNumber(value, [type, [encrypted]]) -> count
    // 阻塞等待异步搜索结束，期间响应中断。
    private inner class SearchNumber : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法搜索")
                return LuaValue.valueOf(0)
            }
            val query = queryOf(args.arg(1))
            val flags = args.arg(2).optint(ScriptTypeFlags.AUTO)
            val encrypted = args.arg(3).optboolean(false)
            val type = resolveSearchType(flags, query, encrypted)
            if (!onStartSearch(query, type)) {
                throw LuaError("gg.searchNumber: 搜索启动失败（可能已有搜索在进行）")
            }
            return LuaValue.valueOf(waitForSearch().toDouble())
        }
    }

    // gg.refineNumber(value, [type]) -> count
    private inner class RefineNumber : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法搜索")
                return LuaValue.valueOf(0)
            }
            val count = onGetResultsCount()
            if (count <= 0L) {
                return LuaValue.valueOf(0)
            }
            val query = queryOf(args.arg(1))
            val flags = args.arg(2).optint(ScriptTypeFlags.AUTO)
            val type = resolveSearchType(flags, query, false)
            if (!onStartRefine(query, type)) {
                throw LuaError("gg.refineNumber: 细化搜索启动失败")
            }
            return LuaValue.valueOf(waitForSearch().toDouble())
        }
    }

    // gg.editAll(value, type) -> count
    // 把当前结果列表里的每一项都改成同一个值，返回成功写入的条数。
    private inner class EditAll : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throwIfInterrupted()
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return LuaValue.valueOf(0)
            }
            val value = args.arg(1)
            val flags = args.arg(2).optint(ScriptTypeFlags.AUTO)
            val type = resolveWriteType(
                ScriptTypeFlags.toDisplayType(flags)
                    ?: throw LuaError("unsupported type flag: $flags"),
                value
            )
            val bytes = encodeValue(value, type) ?: return LuaValue.valueOf(0)
            // 走宿主的分页批量写入，覆盖全部结果而不只是前 MAX_GET_RESULTS 条。
            // 把中断检查传进去，让宿主循环能及时退出。
            val written = onEditAll(bytes) { shouldInterrupt() }
            // 写入过程中被中断时抛出，使脚本按 Stopped/Timeout 收尾，
            // 而不是带着一个"只写了一半"的计数继续往下跑。
            throwIfInterrupted()
            return LuaValue.valueOf(written.toDouble())
        }
    }

    /**
     * 轮询等待异步搜索结束。
     *
     * 每轮先检查中断再读状态：脚本被停止或超时时，先取消搜索再抛错，
     * 避免 native 侧继续扫描已无人关心的结果。
     */
    private fun waitForSearch(): Long {
        try {
            while (true) {
                throwIfInterrupted()
                val status = onSearchStatus()
                if (status.finished) {
                    status.error?.let { throw LuaError("gg: $it") }
                    return status.count
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw LuaError("script interrupted")
                }
            }
        } catch (error: LuaError) {
            // 中断时别把搜索留在后台跑
            onCancelSearch()
            throw error
        }
    }

    /**
     * 取得搜索值的字符串形式。
     *
     * 关键：不能用 isnumber()/isstring() 判定，LuaJ 这两个方法是双向松散的——
     *  - LuaString.isnumber() 对 "123" 这类数字字符串返回 true
     *  - LuaNumber.isstring() 恒返回 true
     * 因此只能用 [LuaValue.type] 区分真实类型：
     *  - 字符串原样透传，否则 gg.searchNumber("9007199254740993", TYPE_QWORD)
     *    会经 double 变成 9007199254740992；
     *  - 数字走 [numberToQueryString]，否则 LuaDouble.tojstring() 会把
     *    非整数先降为 float 再输出，只剩 7 位有效数字。
     */
    private fun queryOf(value: LuaValue): String {
        return when (value.type()) {
            LuaValue.TNIL -> throw LuaError("gg: 搜索值为空")
            // 字符串原样透传，绝不走 double。
            LuaValue.TSTRING -> value.tojstring()
            // 数字不能用 tojstring()：LuaJ 的 LuaDouble.tojstring() 对非整数
            // 是 Float.toString((float)v)，精度只剩 7 位有效数字，
            // 0.1234567890123 会被搜成 0.12345679。
            LuaValue.TNUMBER -> numberToQueryString(value.todouble())
            else -> value.tojstring()
        }
    }

    /**
     * 把 Lua number 转成搜索用的整数/小数字符串。
     *
     * 不能直接 toLong()：1e20 这类超范围的整数值会被饱和成 Long.MAX_VALUE，
     * 于是搜索的是另一个数且不报错。无法表示为 64 位整数时退回科学计数法
     * 字符串，让 native 侧按自己的规则解析（而不是拿到一个被截断的值）。
     */
    private fun numberToQueryString(d: Double): String {
        if (!d.isFinite()) throw LuaError("gg: 搜索值不是有限数值")
        if (d % 1.0 != 0.0) return d.toString()
        val asInteger = integerStringOrNull(d)
        if (asInteger == null) {
            onWarn("gg: 搜索值超出 64 位整数范围，请改用字符串传值")
            return d.toString()
        }
        return asInteger
    }

    private fun resolveSearchType(flags: Int, query: String, encrypted: Boolean): DisplayValueType {
        val declared = ScriptTypeFlags.toDisplayType(flags)
            ?: throw LuaError("unsupported type flag: $flags")
        // GG 的 encrypted=true 表示按 XOR 加密值搜索，需要引擎在读取时先解密。
        // 本项目的搜索引擎只对原始字节做比较，映射到 XOR 只是改了个类型编号，
        // 结果仍是明文匹配——看起来"搜到了"实则完全错误，所以直接拒绝。
        if (encrypted) {
            throw LuaError("gg: 暂不支持加密(encrypted)搜索")
        }
        if (declared != DisplayValueType.AUTO) return declared
        // AUTO 按搜索值本身推断；整数落 Dword/Qword，小数落 Float/Double。
        return ValueTypeUtils.inferAutoType(query)
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
