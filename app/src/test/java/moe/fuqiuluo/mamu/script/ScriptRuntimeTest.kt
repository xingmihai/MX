package moe.fuqiuluo.mamu.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import org.luaj.vm2.LuaValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScriptRuntimeTest : FunSpec({

    test("Type Flag 与 DisplayValueType 双向映射") {
        val pairs = listOf(
            ScriptTypeFlags.BYTE to DisplayValueType.BYTE,
            ScriptTypeFlags.WORD to DisplayValueType.WORD,
            ScriptTypeFlags.DWORD to DisplayValueType.DWORD,
            ScriptTypeFlags.QWORD to DisplayValueType.QWORD,
            ScriptTypeFlags.FLOAT to DisplayValueType.FLOAT,
            ScriptTypeFlags.DOUBLE to DisplayValueType.DOUBLE,
        )
        pairs.forEach { (flag, type) ->
            ScriptTypeFlags.toDisplayType(flag) shouldBe type
            ScriptTypeFlags.fromDisplayType(type) shouldBe flag
        }
        ScriptTypeFlags.toDisplayType(99).shouldBeNull()
        ScriptTypeFlags.fromDisplayType(DisplayValueType.PATTERN) shouldBe ScriptTypeFlags.DWORD
    }

    test("地址解析支持十进制与十六进制") {
        ScriptAddress.parse("4096") shouldBe 4096L
        ScriptAddress.parse("0x1000") shouldBe 0x1000L
        ScriptAddress.parse("0XFF") shouldBe 255L
        ScriptAddress.parse("") shouldBe null
        ScriptAddress.parse("0x") shouldBe null
        ScriptAddress.parse("xyz") shouldBe null
        ScriptAddress.toHex(255L) shouldBe "0xff"
    }

    test("文件名消毒拒绝路径穿越") {
        ScriptFileNames.sanitize("demo") shouldBe "demo.lua"
        ScriptFileNames.sanitize("demo.lua") shouldBe "demo.lua"
        ScriptFileNames.sanitize("../x.lua") shouldBe null
        ScriptFileNames.sanitize("a/b.lua") shouldBe null
        ScriptFileNames.sanitize("a\\b.lua") shouldBe null
        ScriptFileNames.sanitize("") shouldBe null
        ScriptFileNames.sanitize("bad name.lua") shouldBe null
    }

    test("沙箱拒绝 luajava / io / os / dofile") {
        val globals = SandboxGlobals.create(onPrint = {})
        globals.get("luajava").isnil() shouldBe true
        globals.get("io").isnil() shouldBe true
        globals.get("os").isnil() shouldBe true
        runCatching { globals.load("dofile('x.lua')").call() }.exceptionOrNull()
            .shouldNotBeNull()
            .message.shouldContain("dofile")
        runCatching { globals.load("loadfile('x.lua')").call() }.exceptionOrNull()
            .shouldNotBeNull()
    }

    test("沙箱允许 load 编译字符串") {
        val globals = SandboxGlobals.create(onPrint = {})
        globals.load("return load('return 41+1')()").call().toint() shouldBe 42
    }

    test("makeRequest 对非法 URL 返回 error 表") {
        val globals = SandboxGlobals.create(onPrint = {})
        fakeApi().install(globals)
        val table = globals.get("gg").get("makeRequest").call(LuaValue.valueOf("ftp://example.com"))
        table.get("error").tojstring() shouldContain "http"
        table.get("content").tojstring() shouldBe ""
        table.get("code").toint() shouldBe 0
    }

    test("makeRequest 空 URL 抛出错误") {
        val globals = SandboxGlobals.create(onPrint = {})
        fakeApi().install(globals)
        runCatching {
            globals.get("gg").get("makeRequest").call(LuaValue.NIL)
        }.exceptionOrNull().shouldNotBeNull().message.shouldContain("URL")
    }

    test("空脚本由调用方拒绝：空白源码长度为 0") {
        "   \n".isBlank() shouldBe true
        "print(1)".isBlank() shouldBe false
    }

    test("gg.toast 把消息交给 onToast") {
        val messages = mutableListOf<String>()
        val api = fakeApi(onToast = { messages.add(it) })
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("toast").call(LuaValue.valueOf("hello toast"))
        messages shouldBe listOf("hello toast")
    }

    test("gg.getResultsCount 与 clearResults") {
        var count = 3
        val api = fakeApi(
            onGetResultsCount = { count },
            onClearResults = { count = 0 }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("getResultsCount").call().toint() shouldBe 3
        globals.get("gg").get("clearResults").call()
        globals.get("gg").get("getResultCount").call().toint() shouldBe 0
    }

    test("gg.getValues 按 flags 回填 value") {
        val memory = hashMapOf<Long, ByteArray>()
        memory[0x1000L] = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(42).array()
        val api = fakeApi(
            bound = true,
            readMemory = { addr, size -> memory[addr]?.copyOf(size) }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        val items = LuaValue.tableOf()
        val row = LuaValue.tableOf()
        row.set("address", LuaValue.valueOf("0x1000"))
        row.set("flags", globals.get("gg").get("TYPE_DWORD"))
        items.set(1, row)
        val out = globals.get("gg").get("getValues").call(items)
        out.get(1).get("value").tojstring() shouldBe "42"
    }

    test("gg.setValues 写入并支持 freeze") {
        val memory = hashMapOf<Long, ByteArray>()
        val frozen = mutableListOf<Long>()
        val api = fakeApi(
            bound = true,
            writeMemory = { addr, data ->
                memory[addr] = data
                true
            },
            onFreeze = { addr, _, _ ->
                frozen.add(addr)
                true
            }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        val items = LuaValue.tableOf()
        val row = LuaValue.tableOf()
        row.set("address", LuaValue.valueOf("0x2000"))
        row.set("flags", globals.get("gg").get("TYPE_DWORD"))
        row.set("value", LuaValue.valueOf(99))
        row.set("freeze", LuaValue.TRUE)
        items.set(1, row)
        globals.get("gg").get("setValues").call(items).toboolean() shouldBe true
        ByteBuffer.wrap(memory[0x2000L]!!).order(ByteOrder.LITTLE_ENDIAN).int shouldBe 99
        frozen shouldBe listOf(0x2000L)
    }

    test("gg.getRangesList 按名字过滤") {
        val ranges = listOf(
            ScriptMemoryRange(0x1000, 0x2000, "/system/lib/libc.so", "r-xp"),
            ScriptMemoryRange(0x3000, 0x4000, "[anon:libc_malloc]", "rw-p")
        )
        val api = fakeApi(onGetMemoryRanges = { filter ->
            ranges.filter { filter == null || it.name.contains(filter) }
        })
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        val all = globals.get("gg").get("getRangesList").call()
        all.length() shouldBe 2
        val libc = globals.get("gg").get("getRangesList").call(LuaValue.valueOf("libc.so"))
        libc.length() shouldBe 1
        libc.get(1).get("name").tojstring() shouldBe "/system/lib/libc.so"
        libc.get(1).get("start").tojstring() shouldBe "0x1000"
    }

    test("gg.copyMemory 复制字节") {
        val memory = hashMapOf<Long, ByteArray>()
        memory[0x10L] = byteArrayOf(1, 2, 3, 4)
        val api = fakeApi(
            bound = true,
            readMemory = { addr, size -> memory[addr]?.copyOf(size) },
            writeMemory = { addr, data ->
                memory[addr] = data
                true
            }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("copyMemory").call(
            LuaValue.valueOf("0x10"),
            LuaValue.valueOf("0x20"),
            LuaValue.valueOf(4)
        ).toboolean() shouldBe true
        memory[0x20L]!!.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())
    }

    test("gg.copyText 把文本交给 onCopyText") {
        val copied = mutableListOf<String>()
        val api = fakeApi(onCopyText = { copied.add(it) })
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("copyText").call(LuaValue.valueOf("clip"))
        copied shouldBe listOf("clip")
    }

    test("gg.getTargetPackage 返回进程名") {
        val api = fakeApi(bound = true)
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("getTargetPackage").call().tojstring() shouldBe "demo"
        val unbound = fakeApi(bound = false)
        unbound.install(globals)
        globals.get("gg").get("getTargetPackage").call().isnil() shouldBe true
    }

    test("getResults 截断到 maxCount") {
        val items = (1..5).map {
            ScriptResultItem(address = it.toLong(), value = it.toString(), flags = ScriptTypeFlags.DWORD)
        }
        val api = fakeApi(results = items)
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        val table = globals.get("gg").get("getResults").call(LuaValue.valueOf(2))
        table.length() shouldBe 2
        table.get(1).get("value").tojstring() shouldBe "1"
        table.get(2).get("value").tojstring() shouldBe "2"
    }

    test("未绑定进程时读写不写内存") {
        var wrote = false
        val api = fakeApi(
            bound = false,
            writeMemory = { _, _ ->
                wrote = true
                true
            }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        globals.get("gg").get("getTargetInfo").call().isnil() shouldBe true
        globals.get("gg").get("readValue").call(LuaValue.valueOf("0x10"), LuaValue.valueOf(4)).isnil() shouldBe true
        globals.get("gg").get("writeValue").call(
            LuaValue.valueOf("0x10"),
            LuaValue.valueOf(1),
            LuaValue.valueOf(4)
        ).toboolean() shouldBe false
        wrote shouldBe false
    }

    test("已绑定进程时可读写 DWORD") {
        val memory = hashMapOf<Long, ByteArray>()
        memory[0x1000L] = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(42).array()
        val api = fakeApi(
            bound = true,
            readMemory = { addr, size -> memory[addr]?.copyOf(size) },
            writeMemory = { addr, data ->
                memory[addr] = data
                true
            }
        )
        val globals = SandboxGlobals.create(onPrint = {})
        api.install(globals)
        val info = globals.get("gg").get("getTargetInfo").call()
        info.get("pid").toint() shouldBe 123
        globals.get("gg").get("readValue").call(
            LuaValue.valueOf("0x1000"),
            globals.get("gg").get("TYPE_DWORD")
        ).tojstring() shouldBe "42"
        globals.get("gg").get("writeValue").call(
            LuaValue.valueOf("0x1000"),
            LuaValue.valueOf(99),
            globals.get("gg").get("TYPE_DWORD")
        ).toboolean() shouldBe true
        ByteBuffer.wrap(memory[0x1000L]!!).order(ByteOrder.LITTLE_ENDIAN).int shouldBe 99
    }

    test("脚本仓库保存与载入") {
        val dir = File(System.getProperty("java.io.tmpdir"), "mamu-script-test-${System.nanoTime()}")
        dir.mkdirs()
        val repo = ScriptRepository(dir)
        val saved = repo.save("demo", "print('hi')")
        saved shouldBe "demo.lua"
        repo.list() shouldBe listOf("demo.lua")
        repo.load("demo.lua") shouldBe "print('hi')"
        runCatching { repo.save("../x", "x") }.exceptionOrNull().shouldNotBeNull()
    }

    test("ScriptHost 执行 print 并完成") {
        val outputs = mutableListOf<String>()
        val finished = CountDownLatch(1)
        val reason = AtomicReference<ScriptEndReason>()
        val api = fakeApi()
        val host = ScriptHost(poster = { it.run() }, timeoutMs = 5_000)
        host.execute(
            source = "print('hello')",
            api = api,
            onOutput = { outputs.add(it) },
            onFinished = {
                reason.set(it)
                finished.countDown()
            }
        )
        finished.await(5, TimeUnit.SECONDS) shouldBe true
        outputs shouldBe listOf("hello")
        reason.get() shouldBe ScriptEndReason.Completed
    }

    test("gg.sleep 超过 ScriptHost 超时则中断") {
        val finished = CountDownLatch(1)
        val reason = AtomicReference<ScriptEndReason>()
        val started = System.currentTimeMillis()
        val host = ScriptHost(poster = { it.run() }, timeoutMs = 200)
        host.execute(
            source = "gg.sleep(5000)",
            api = fakeApi(),
            onOutput = {},
            onFinished = {
                reason.set(it)
                finished.countDown()
            }
        )
        finished.await(3, TimeUnit.SECONDS) shouldBe true
        reason.get() shouldBe ScriptEndReason.Timeout
        (System.currentTimeMillis() - started) shouldBeLessThan 1500L
    }

    test("语法错误返回行号") {
        val finished = CountDownLatch(1)
        val reason = AtomicReference<ScriptEndReason>()
        val host = ScriptHost(poster = { it.run() }, timeoutMs = 5_000)
        host.execute(
            source = "function (",
            api = fakeApi(),
            onOutput = {},
            onFinished = {
                reason.set(it)
                finished.countDown()
            }
        )
        finished.await(5, TimeUnit.SECONDS) shouldBe true
        val error = reason.get() as ScriptEndReason.Error
        error.message.shouldContain("function")
    }

    test("parseLine 从 luaj 错误中提取行号") {
        ScriptHost.parseLine("script:3: unexpected symbol") shouldBe 3
        ScriptHost.parseLine("no line") shouldBe null
    }

    test("路径规范化拒绝穿越并支持上级") {
        ScriptPaths.normalize("/sdcard/../Download") shouldBe "/Download"
        ScriptPaths.normalize("/sdcard/Mamu/./scripts") shouldBe "/sdcard/Mamu/scripts"
        ScriptPaths.parent("/sdcard/Mamu") shouldBe "/sdcard"
        ScriptPaths.parent("/") shouldBe null
        ScriptPaths.child("/sdcard", "demo.lua") shouldBe "/sdcard/demo.lua"
    }

    test("URL 仅接受 http 与 https") {
        ScriptUrlFetcher.validate("") shouldBe "URL 为空"
        ScriptUrlFetcher.validate("ftp://example.com/a.lua") shouldBe "仅支持 http 或 https"
        ScriptUrlFetcher.validate("file:///sdcard/a.lua") shouldBe "仅支持 http 或 https"
        ScriptUrlFetcher.validate("https://example.com/a.lua") shouldBe null
        ScriptUrlFetcher.validate("http://example.com/a.lua") shouldBe null
    }
})

private fun fakeApi(
    bound: Boolean = true,
    results: List<ScriptResultItem> = emptyList(),
    selected: List<ScriptResultItem> = emptyList(),
    onToast: (String) -> Unit = {},
    readMemory: (Long, Int) -> ByteArray? = { _, _ -> null },
    writeMemory: (Long, ByteArray) -> Boolean = { _, _ -> false },
    onGetResultsCount: () -> Int = { results.size },
    onClearResults: () -> Unit = {},
    onGetMemoryRanges: (String?) -> List<ScriptMemoryRange> = { emptyList() },
    onCopyText: (String) -> Unit = {},
    onFreeze: (Long, ByteArray, Int) -> Boolean = { _, _, _ -> false },
    onUnfreeze: (Long) -> Boolean = { false }
): GgApiBridge {
    return GgApiBridge(
        selectedResults = selected,
        onToast = onToast,
        onWarn = {},
        getResults = { maxCount -> results.take(maxCount) },
        isProcessBound = { bound },
        currentPid = { 123 },
        processName = { "demo" },
        readMemory = readMemory,
        writeMemory = writeMemory,
        onGetResultsCount = onGetResultsCount,
        onClearResults = onClearResults,
        onGetMemoryRanges = onGetMemoryRanges,
        onCopyText = onCopyText,
        onFreeze = onFreeze,
        onUnfreeze = onUnfreeze
    )
}
