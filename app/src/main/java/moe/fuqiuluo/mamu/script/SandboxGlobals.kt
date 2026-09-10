package moe.fuqiuluo.mamu.script

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.OsLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction

object SandboxGlobals {
    fun create(
        onPrint: (String) -> Unit,
        debugHook: DebugLib? = null
    ): Globals {
        val globals = Globals()
        globals.load(SandboxedBaseLib(onPrint))
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(MathLib())
        globals.load(SandboxedOsLib())
        if (debugHook != null) {
            globals.load(debugHook)
        }
        LoadState.install(globals)
        LuaC.install(globals)
        stripUnsafe(globals)
        return globals
    }

    fun stripUnsafe(globals: Globals) {
        listOf(
            "luajava",
            "io",
            "package",
            "require",
            "module",
            "collectgarbage",
            "rawset",
            "rawget",
            "rawequal",
            "setfenv",
            "getfenv",
            "debug"
        ).forEach { name ->
            globals.set(name, LuaValue.NIL)
        }
    }

    private class SandboxedBaseLib(
        private val onPrint: (String) -> Unit
    ) : org.luaj.vm2.lib.BaseLib() {
        override fun call(modname: LuaValue, env: LuaValue): LuaValue {
            super.call(modname, env)
            env.set("print", PrintFunction(onPrint))
            env.set("dofile", ForbiddenFunction("dofile"))
            env.set("loadfile", ForbiddenFunction("loadfile"))
            return env
        }
    }

    /**
     * 安全的 os 库子集:clock/date/time/getenv 保留,exit 替换为 SafeExit,
     * 危险函数(execute/remove/rename/tmpname/setlocale)替换为 ForbiddenFunction。
     * os.exit 不终止 JVM,而是抛出 [ScriptExit] 由 ScriptHost 捕获为正常完成。
     */
    private class SandboxedOsLib : OsLib() {
        override fun call(modname: LuaValue, env: LuaValue): LuaValue {
            super.call(modname, env)
            val osTable = env.get("os")
            osTable.set("execute", ForbiddenFunction("os.execute"))
            osTable.set("remove", ForbiddenFunction("os.remove"))
            osTable.set("rename", ForbiddenFunction("os.rename"))
            osTable.set("tmpname", ForbiddenFunction("os.tmpname"))
            osTable.set("setlocale", ForbiddenFunction("os.setlocale"))
            osTable.set("exit", SafeExit())
            return osTable
        }
    }

    /** os.exit 不终止 JVM,而是抛出 ScriptExit 让宿主正常结束脚本。 */
    private class SafeExit : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val code = args.optint(1, 0)
            throw ScriptExit("os.exit($code)")
        }
    }

    private class PrintFunction(
        private val onPrint: (String) -> Unit
    ) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val builder = StringBuilder()
            for (i in 1..args.narg()) {
                if (i > 1) builder.append('\t')
                builder.append(args.arg(i).tojstring())
            }
            onPrint(builder.toString())
            return NONE
        }
    }

    private class ForbiddenFunction(private val name: String) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            throw LuaError("$name is disabled")
        }
    }
}

/** os.exit 抛出,由 ScriptHost 捕获为正常完成(ScriptEndReason.Completed)。 */
class ScriptExit(message: String) : LuaError(message)

class InterruptDebugLib(
    private val shouldStop: () -> Boolean
) : DebugLib() {
    @Volatile
    private var instructionCount = 0

    override fun onInstruction(pc: Int, v: Varargs, top: Int) {
        instructionCount++
        if (instructionCount % 64 == 0 && shouldStop()) {
            throw LuaError("script interrupted")
        }
    }
}
