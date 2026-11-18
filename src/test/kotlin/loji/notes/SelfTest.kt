package loji.notes

import loji.notes.anchor.Anchor
import loji.notes.model.Note
import loji.notes.model.Palette
import loji.notes.store.Json
import loji.notes.store.JsonNumber
import loji.notes.store.JsonObject
import loji.notes.store.JsonString
import loji.notes.store.NoteStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * 纯逻辑自测：不启动 IDE，直接 `java -cp` 跑。
 * 覆盖 JSON、侧车存储、锚点指纹与重定位。
 */
object SelfTest {

    private var pass = 0
    private var fail = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            pass++
        } else {
            fail++
            println("FAIL  $name  $detail")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        testJson()
        testStore()
        testAnchor()
        testPaths()
        testPalette()
        testSample()
        println()
        println("== self-test: $pass passed, $fail failed ==")
        if (fail > 0) kotlin.system.exitProcess(1)
    }

    private fun testJson() {
        val obj = JsonObject()
        obj.put("text", "中文 \"引号\" 换行\n制表\t结束")
        obj.put("n", 42)
        obj.put("big", 1700000000000L)
        obj.put("ok", true)
        val arr = loji.notes.store.JsonArray()
        arr.add(JsonString("a"))
        arr.add(JsonNumber("-1.5"))
        obj.put("arr", arr)

        val text = Json.write(obj)
        val back = Json.parse(text) as JsonObject
        check("json string roundtrip", back.string("text") == "中文 \"引号\" 换行\n制表\t结束", back.string("text"))
        check("json int", back.int("n") == 42)
        check("json long", back.long("big") == 1700000000000L)
        check("json bool", back.bool("ok"))
        check("json array", back.array("arr")?.items?.size == 2)
        check("json literal", Json.parse("{\"a\":null,\"b\":[]}") .let { it is JsonObject && it["b"] is loji.notes.store.JsonArray })
        check("json empty object", Json.parse("{}") is JsonObject)

        var threw = false
        try {
            Json.parse("{\"a\": }")
        } catch (_: Exception) {
            threw = true
        }
        check("json rejects malformed", threw)
    }

    private fun testStore() {
        val root: Path = Files.createTempDirectory("loji-test")
        try {
            val store = NoteStore(root)
            val rel = "src/main/java/com/demo/Foo.java"
            val notes = mutableListOf(
                Note(
                    id = "n_1",
                    line = 12,
                    fingerprint = Anchor.fingerprint("public void run() {"),
                    ctxBefore = listOf("@Override"),
                    ctxAfter = listOf("    start();"),
                    text = "这里要加超时控制",
                    color = "blue",
                    dx = 88,
                    dy = -20,
                    collapsed = false,
                    createdAt = 111L,
                    updatedAt = 222L,
                    author = "loji",
                ),
            )
            store.saveAtomic(rel, notes)
            check("store file created", Files.exists(store.pathFor(rel)))
            check("store path mirrors", store.pathFor(rel).toString().endsWith("Foo.java.json"))

            val loaded = store.load(rel)
            check("store count", loaded.size == 1)
            val n = loaded.first()
            check("store text", n.text == "这里要加超时控制", n.text)
            check("store color", n.color == "blue")
            check("store offset", n.dx == 88 && n.dy == -20)
            check("store ctx", n.ctxBefore == listOf("@Override") && n.ctxAfter == listOf("    start();"))
            check("store timestamps", n.createdAt == 111L && n.updatedAt == 222L)

            check("store readme", Files.exists(root.resolve(".loji").resolve("README.md")))

            var rejected = false
            try {
                store.pathFor("../escape.txt")
            } catch (_: Exception) {
                rejected = true
            }
            check("store rejects traversal", rejected)

            check("store missing file is empty", store.load("nope/NotThere.txt").isEmpty())
        } finally {
            deleteRecursively(root)
        }
    }

    private fun testAnchor() {
        val code = """
            package demo;
            public class Demo {
                public void run() {
                    start();
                    wait();
                }
            }
        """.trimIndent().lines()

        val anchorLine = 3 // start();
        val (before, after) = Anchor.context(code, anchorLine)
        val note = Note(
            id = "a",
            line = anchorLine,
            fingerprint = Anchor.fingerprint(code[anchorLine]),
            ctxBefore = before,
            ctxAfter = after,
        )

        check("anchor fingerprint stable", Anchor.fingerprint("  public void run() { ") == Anchor.fingerprint("public void run() {"))
        check("anchor fingerprint differs", Anchor.fingerprint("a();") != Anchor.fingerprint("b();"))
        check("anchor exact hit", Anchor.relocate(note, code) == anchorLine)

        // 上方插了 3 行：应跟着往下漂
        val shifted = mutableListOf(
            "// 新增一",
            "// 新增二",
            "// 新增三",
        ) + code
        val moved = Anchor.relocate(note, shifted)
        check("anchor follows inserted lines", moved == anchorLine + 3, "got=$moved")

        // 目标行本身被改写，但上下文还在
        val edited = code.toMutableList()
        edited[anchorLine] = "    startWithTimeout(30);"
        val editedHit = Anchor.relocate(note, edited)
        check("anchor survives target edit", editedHit == anchorLine, "got=$editedHit")

        // 整个文件被换掉：应该判定失联
        val replaced = List(code.size) { "totally different line $it" }
        check("anchor lost when file replaced", Anchor.relocate(note, replaced) == null)

        // 删掉锚点行？上下文不再匹配 → 允许 null，但绝不能指到无关行
        val list = mutableListOf(note)
        check("anchor resolveAll preserves clean state", Anchor.resolveAll(list, code).not() || list.first().line == anchorLine)
    }

    /** 路径归一化：Windows 的反斜杠 basePath 对上 VFS 的正斜杠 filePath。 */
    private fun testPaths() {
        check(
            "relative key windows base",
            NoteStore.relativeKey(
                "C:\\work\\sample",
                "C:/work/sample/src/main/java/com/demo/pay/SettlementService.java",
            ) == "src/main/java/com/demo/pay/SettlementService.java",
        )
        check(
            "relative key mixed separators",
            NoteStore.relativeKey("C:/proj/", "C:\\proj\\a\\b.java") == "a/b.java",
        )
        check("relative key drive case", NoteStore.relativeKey("c:\\proj", "C:/proj/x.java") == "x.java")
        check("relative key rejects sibling", NoteStore.relativeKey("C:\\proj", "C:/proj-other/x.java") == null)
        check("relative key rejects equal path", NoteStore.relativeKey("C:\\proj", "C:/proj") == null)
        check("relative key rejects outside", NoteStore.relativeKey("C:\\proj", "D:/proj/x.java") == null)
    }

    private fun testPalette() {
        check("palette cycle", Palette.nextId("amber") == "green")
        check("palette wraps", Palette.nextId("gray") == "amber")
        check("palette unknown falls back", Palette.byId("nope").id == Palette.DEFAULT_ID)
        check("palette colors count", Palette.colors.size == 6)
    }

    /** 校验 sample 演示工程的数据能被真实解析、锚点能对上源码行。 */
    private fun testSample() {
        val projectRoot = java.nio.file.Paths.get("sample")
        val rel = "src/main/java/com/demo/pay/SettlementService.java"
        if (!Files.exists(projectRoot.resolve(rel))) {
            println("SKIP  sample project not found")
            return
        }
        val store = NoteStore(projectRoot)
        val notes = store.load(rel)
        check("sample loads notes", notes.size == 3, "got=${notes.size}")
        val lines = Files.readAllLines(projectRoot.resolve(rel))
        for (n in notes) {
            check("sample anchor fingerprint L${n.line + 1}",
                n.line in lines.indices && Anchor.fingerprint(lines[n.line]) == n.fingerprint)
            check("sample anchor relocates L${n.line + 1}", Anchor.relocate(n, lines) == n.line)
        }
        check("sample colors distinct", notes.map { it.color }.toSet().size == 3)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
