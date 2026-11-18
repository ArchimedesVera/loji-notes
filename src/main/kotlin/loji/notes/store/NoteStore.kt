package loji.notes.store

import loji.notes.model.Note
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 侧车存储：每个源文件一个 `.loji/notes/<相对路径>.json`。
 *
 * 放在仓库里，跟着 git 走，所以别人 clone 下来就能看到同一批注释。
 * 这里只用 java.nio，不碰 IDE API，方便脱离 IDE 做单元自测。
 */
class NoteStore(val projectRoot: Path) {

    companion object {
        const val DIR = ".loji"
        const val NOTES_SUBDIR = "notes"
        const val FORMAT = "loji-notes"
        const val VERSION = 1

        fun newId(): String = "n_" + UUID.randomUUID().toString().replace("-", "").take(10)

        fun fileKey(relPath: String): String = relPath.replace('\\', '/').trimStart('/')

        /**
         * 从项目根和文件绝对路径算出侧车用的相对路径。
         *
         * 注意：`VirtualFile.getPath()` 用 `/`，`Project.getBasePath()` 在 Windows 上给的是 `\`，
         * 直接 startsWith 永远不成立，所以两边都先归一化。
         */
        fun relativeKey(basePath: String, filePath: String): String? {
            val base = basePath.replace('\\', '/').trimEnd('/')
            val path = filePath.replace('\\', '/')
            if (base.isEmpty()) return null
            if (path.length <= base.length + 1) return null
            if (!path.startsWith("$base/", ignoreCase = true)) return null
            return path.substring(base.length + 1)
        }
    }

    fun notesDir(): Path = projectRoot.resolve(DIR).resolve(NOTES_SUBDIR)

    fun pathFor(relPath: String): Path {
        val key = fileKey(relPath)
        require(key.isNotEmpty()) { "空路径" }
        require(!key.contains("..")) { "非法路径: $key" }
        require(!Regex("[:*?\"<>|]").containsMatchIn(key)) { "非法路径: $key" }
        val p = notesDir().resolve("$key.json").normalize()
        require(p.startsWith(notesDir().normalize())) { "路径越界: $key" }
        return p
    }

    fun exists(relPath: String): Boolean = Files.exists(pathFor(relPath))

    fun load(relPath: String): MutableList<Note> {
        val path = pathFor(relPath)
        if (!Files.isRegularFile(path)) return mutableListOf()
        val text = Files.readString(path, StandardCharsets.UTF_8)
        val root = Json.parse(text) as? JsonObject ?: return mutableListOf()
        val arr = root.array("notes") ?: return mutableListOf()
        val out = mutableListOf<Note>()
        for (item in arr.items) {
            val o = item as? JsonObject ?: continue
            out += Note(
                id = o.string("id").ifEmpty { newId() },
                line = o.int("line"),
                fingerprint = o.string("fingerprint"),
                ctxBefore = o.array("ctxBefore")?.items?.mapNotNull { (it as? JsonString)?.value } ?: emptyList(),
                ctxAfter = o.array("ctxAfter")?.items?.mapNotNull { (it as? JsonString)?.value } ?: emptyList(),
                text = o.string("text"),
                color = o.string("color", loji.notes.model.Palette.DEFAULT_ID),
                dx = o.int("dx", Note.DEFAULT_DX),
                dy = o.int("dy", Note.DEFAULT_DY),
                collapsed = o.bool("collapsed"),
                createdAt = o.long("created", System.currentTimeMillis()),
                updatedAt = o.long("updated", System.currentTimeMillis()),
                author = o.string("author"),
            )
        }
        return out
    }

    fun save(relPath: String, notes: List<Note>) {
        val path = pathFor(relPath)
        Files.createDirectories(path.parent)
        ensureReadme()
        Files.writeString(path, writeDocument(relPath, notes), StandardCharsets.UTF_8)
    }

    fun writeDocument(relPath: String, notes: List<Note>): String {
        val root = JsonObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("file", fileKey(relPath))
        val arr = JsonArray()
        for (n in notes.sortedWith(compareBy({ it.line }, { it.createdAt }))) {
            val o = JsonObject()
            o.put("id", n.id)
            o.put("line", n.line)
            o.put("fingerprint", n.fingerprint)
            o.put("ctxBefore", JsonArray(n.ctxBefore.mapTo(mutableListOf<JsonValue>()) { JsonString(it) }))
            o.put("ctxAfter", JsonArray(n.ctxAfter.mapTo(mutableListOf<JsonValue>()) { JsonString(it) }))
            o.put("text", n.text)
            o.put("color", n.color)
            o.put("dx", n.dx)
            o.put("dy", n.dy)
            o.put("collapsed", n.collapsed)
            o.put("created", n.createdAt)
            o.put("updated", n.updatedAt)
            o.put("author", n.author)
            arr.add(o)
        }
        root.put("notes", arr)
        return Json.write(root)
    }

    fun delete(relPath: String) {
        val path = pathFor(relPath)
        Files.deleteIfExists(path)
    }

    private fun ensureReadme() {
        val readme = projectRoot.resolve(DIR).resolve("README.md")
        if (Files.exists(readme)) return
        Files.createDirectories(readme.parent)
        val text = """
            # .loji/notes

            这里是 **Loji Notes 注释栏** 的数据目录：每张注释卡对应 `notes/` 下一个与源文件同名的 `.json`。

            建议把这个目录**提交进版本库**。这样别人 clone 或切分支时，注释栏会跟着代码一起出现。

            - 锚点：行号 + 目标行指纹 + 上下各 2 行上下文，代码挪动后会自动重新定位。
            - 卡片位置：`dx` / `dy` 是相对锚点（行尾）的像素偏移。
            - 颜色：`amber` `green` `blue` `purple` `red` `gray`。

            想临时不看这些注释，把目录加进 `.git/info/exclude`，或者直接删掉对应 json；插件没有别处状态。
        """.trimIndent() + "\n"
        Files.writeString(readme, text, StandardCharsets.UTF_8)
    }

    /** 原子写：先写临时文件再改名，避免 IDE 崩溃时留下半截 JSON。 */
    fun saveAtomic(relPath: String, notes: List<Note>) {
        val path = pathFor(relPath)
        Files.createDirectories(path.parent)
        ensureReadme()
        val tmp = path.resolveSibling(path.fileName.toString() + "." + System.nanoTime() + ".tmp")
        Files.writeString(tmp, writeDocument(relPath, notes), StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
