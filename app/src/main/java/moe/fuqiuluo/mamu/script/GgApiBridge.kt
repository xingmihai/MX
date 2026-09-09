package moe.fuqiuluo.mamu.script

import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.PointerChainResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction

class GgApiBridge(
    private val selectedResults: List<ScriptResultItem>,
    private val onToast: (String) -> Unit,
    private val onWarn: (String) -> Unit,
    private val getResults: (Int) -> List<ScriptResultItem>,
    private val isProcessBound: () -> Boolean,
    private val currentPid: () -> Int,
    private val processName: () -> String?,
    private val readMemory: (Long, Int) -> ByteArray?,
    private val writeMemory: (Long, ByteArray) -> Boolean
) {
    fun install(globals: org.luaj.vm2.Globals) {
        val gg = LuaTable()
        gg.set("TYPE_BYTE", ScriptTypeFlags.BYTE)
        gg.set("TYPE_WORD", ScriptTypeFlags.WORD)
        gg.set("TYPE_DWORD", ScriptTypeFlags.DWORD)
        gg.set("TYPE_FLOAT", ScriptTypeFlags.FLOAT)
        gg.set("TYPE_QWORD", ScriptTypeFlags.QWORD)
        gg.set("TYPE_DOUBLE", ScriptTypeFlags.DOUBLE)
        gg.set("getTargetInfo", GetTargetInfo())
        gg.set("readValue", ReadValue())
        gg.set("writeValue", WriteValue())
        gg.set("toast", ToastFn())
        gg.set("getResults", GetResults())
        gg.set("getSelectedResults", GetSelectedResults())
        gg.set("makeRequest", MakeRequest())
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

    private inner class ReadValue : TwoArgFunction() {
        override fun call(address: LuaValue, type: LuaValue): LuaValue {
            if (!isProcessBound()) {
                onWarn("未绑定进程，无法读写内存")
                return NIL
            }
            val addr = parseAddress(address) ?: return NIL
            val flags = type.checkint()
            val displayType = ScriptTypeFlags.toDisplayType(flags)
                ?: throw org.luaj.vm2.LuaError("unsupported type flag: $flags")
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
                return LuaValue.FALSE
            }
            val addr = parseAddress(address) ?: return LuaValue.FALSE
            val flags = type.checkint()
            val displayType = ScriptTypeFlags.toDisplayType(flags)
                ?: throw org.luaj.vm2.LuaError("unsupported type flag: $flags")
            val raw = when {
                value.isnumber() && (displayType == DisplayValueType.FLOAT ||
                    displayType == DisplayValueType.DOUBLE) ->
                    value.todouble().toString()
                value.isnumber() -> value.todouble().toLong().toString()
                else -> value.checkjstring()
            }
            val bytes = try {
                ValueTypeUtils.parseExprToBytes(raw, displayType)
            } catch (_: Exception) {
                return LuaValue.FALSE
            }
            return LuaValue.valueOf(writeMemory(addr, bytes))
        }
    }

    private inner class ToastFn : OneArgFunction() {
        override fun call(message: LuaValue): LuaValue {
            onToast(message.tojstring())
            return NONE
        }
    }

    private inner class GetResults : OneArgFunction() {
        override fun call(maxCount: LuaValue): LuaValue {
            val limit = if (maxCount.isnil()) Int.MAX_VALUE else maxCount.checkint().coerceAtLeast(0)
            return toLuaResultTable(getResults(limit))
        }
    }

    private inner class GetSelectedResults : ZeroArgFunction() {
        override fun call(): LuaValue {
            return toLuaResultTable(selectedResults)
        }
    }

    private inner class MakeRequest : OneArgFunction() {
        override fun call(url: LuaValue): LuaValue {
            val result = ScriptUrlFetcher.request(url.tojstring())
            val table = LuaTable()
            table.set("code", result.code)
            table.set("url", result.url)
            table.set("content", result.content)
            if (result.error == null) {
                table.set("error", LuaValue.FALSE)
            } else {
                table.set("error", result.error)
            }
            return table
        }
    }

    private fun parseAddress(value: LuaValue): Long? {
        return when {
            value.isstring() -> ScriptAddress.parse(value.tojstring())
            value.isnumber() -> ScriptAddress.parseNumber(value.todouble())
            else -> null
        }
    }

    companion object {
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
}
