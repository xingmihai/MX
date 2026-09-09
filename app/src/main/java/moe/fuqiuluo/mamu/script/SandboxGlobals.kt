package moe.fuqiuluo.mamu.script

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
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
            "os",
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
            env.set("load", ForbiddenFunction("load"))
            env.set("loadstring", ForbiddenFunction("loadstring"))
            return env
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

class InterruptDebugLib(
    private val shouldStop: () -> Boolean
) : DebugLib() {
    @Volatile
    private var instructionCount = 0

    override fun onInstruction(pc: Int, v: Varargs, top: Int) {
        instructionCount++
        if (instructionCount % 256 == 0 && shouldStop()) {
            throw LuaError("script interrupted")
        }
    }
}
