package loji.notes

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import loji.notes.anchor.Anchor
import loji.notes.model.Note
import loji.notes.store.NoteStore
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 项目级注释仓库：负责「文件 -> 注释列表」的缓存、锚点校正、落盘与变更广播。
 */
@Service(Service.Level.PROJECT)
class LojiService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<LojiService>()
        private const val KEY_ENABLED = "loji.notes.enabled"

        fun get(project: Project): LojiService = project.service()
    }

    private val root: Path? by lazy {
        project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }
    }

    private val store: NoteStore? by lazy { root?.let { NoteStore(it) } }

    private val cache = HashMap<String, MutableList<Note>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)

    var enabled: Boolean
        get() = props.getBoolean(KEY_ENABLED, true)
        set(value) {
            props.setValue(KEY_ENABLED, value, true)
            fireChanged()
        }

    fun isAvailable(): Boolean = store != null

    // ---------------------------------------------------------------- 读取

    fun keyOf(file: VirtualFile): String? {
        val base = project.basePath ?: return null
        if (file.fileSystem !is LocalFileSystem) return null
        if (!file.isValid || file.isDirectory) return null
        val rel = NoteStore.relativeKey(base, file.path) ?: return null
        if (rel.isEmpty()) return null
        if (rel.replace('\\', '/').startsWith(NoteStore.DIR + "/")) return null
        return NoteStore.fileKey(rel)
    }

    /** 该编辑器所属文件的所有注释（已尝试做锚点校正）。 */
    fun notesFor(editor: Editor): List<Note> {
        val file = editor.virtualFile ?: return emptyList()
        val key = keyOf(file) ?: return emptyList()
        val list = cache.getOrPut(key) { loadAndResolve(key, editor.document) }
        return list
    }

    private fun loadAndResolve(key: String, document: Document): MutableList<Note> {
        val st = store ?: return mutableListOf()
        val list = try {
            st.load(key)
        } catch (e: Exception) {
            LOG.warn("读取注释失败: $key", e)
            mutableListOf()
        }
        val lines = documentLines(document)
        if (Anchor.resolveAll(list, lines)) {
            persist(key, list)
        }
        return list
    }

    // ---------------------------------------------------------------- 写入

    fun addNote(editor: Editor, line: Int): Note? {
        val key = editor.virtualFile?.let { keyOf(it) } ?: return null
        val document = editor.document
        val safeLine = line.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lines = documentLines(document)
        val (before, after) = Anchor.context(lines, safeLine)
        val note = Note(
            id = NoteStore.newId(),
            line = safeLine,
            fingerprint = Anchor.fingerprint(lines.getOrElse(safeLine) { "" }),
            ctxBefore = before,
            ctxAfter = after,
            text = "",
            color = loji.notes.model.Palette.DEFAULT_ID,
            author = System.getProperty("user.name") ?: "",
        )
        val list = cache.getOrPut(key) { loadAndResolve(key, document) }
        list.add(note)
        persist(key, list)
        fireChanged()
        return note
    }

    fun updateNote(editor: Editor, note: Note) {
        val key = editor.virtualFile?.let { keyOf(it) } ?: return
        val list = cache.getOrPut(key) { loadAndResolve(key, editor.document) }
        val i = list.indexOfFirst { it.id == note.id }
        if (i < 0) return
        list[i] = note.copy(updatedAt = System.currentTimeMillis())
        persist(key, list)
        fireChanged()
    }

    fun deleteNote(editor: Editor, id: String) {
        val key = editor.virtualFile?.let { keyOf(it) } ?: return
        val list = cache.getOrPut(key) { loadAndResolve(key, editor.document) }
        if (list.removeAll { it.id == id }) {
            persist(key, list)
            fireChanged()
        }
    }

    /** 文档变化后去抖重算锚点，避免编辑时每个按键都全量搜索。 */
    fun scheduleResolve(editor: Editor) {
        val file = editor.virtualFile ?: return
        val key = keyOf(file) ?: return
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed) return@addRequest
            val doc = editor.document
            val list = cache[key] ?: return@addRequest
            if (Anchor.resolveAll(list, documentLines(doc))) {
                persist(key, list)
                fireChanged()
            }
        }, 300)
    }

    // ---------------------------------------------------------------- 杂项

    private fun documentLines(document: Document): List<String> {
        val n = document.lineCount
        val out = ArrayList<String>(n)
        val text = document.charsSequence
        for (i in 0 until n) {
            val s = document.getLineStartOffset(i)
            val e = document.getLineEndOffset(i)
            out.add(text.subSequence(s, e).toString())
        }
        return out
    }

    private fun persist(key: String, list: List<Note>) {
        val st = store ?: return
        val snapshot = list.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                st.saveAtomic(key, snapshot)
            } catch (e: Exception) {
                LOG.warn("写入注释失败: $key", e)
            }
        }
    }

    fun addListener(listener: () -> Unit): Disposable {
        listeners.add(listener)
        val d = Disposable { listeners.remove(listener) }
        Disposer.register(this, d)
        return d
    }

    fun fireChanged() {
        val app = ApplicationManager.getApplication()
        val run = { listeners.toList().forEach { runCatching { it() } } }
        if (app.isDispatchThread) run() else app.invokeLater(run)
    }

    override fun dispose() {
        cache.clear()
        listeners.clear()
    }
}
