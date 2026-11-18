package loji.notes.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import loji.notes.LojiService
import loji.notes.model.Note
import loji.notes.model.NoteColor
import loji.notes.model.Palette
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * 铺在 IDE 窗口最上层的透明画布。
 *
 * 只画注释卡，不参与编辑器布局；`contains()` 做了命中裁剪，所以卡片以外的地方点击会直接漏给编辑器。
 */
class NoteOverlay(private val project: Project) : JComponent(), Disposable {

    private companion object {
        const val CARD_W = 300
        const val PAD = 12
        const val HEADER_H = 26
        const val LINE_H = 17
        const val RADIUS = 10
        const val MAX_BODY_LINES = 6
    }

    private val service = LojiService.get(project)
    private val attached = HashSet<Editor>()
    private val serviceListener: Disposable

    private var hoverId: String? = null
    private var drag: DragState? = null
    private var preview: Note? = null
    private var warned = false
    private val viewportHooks = ArrayList<Pair<javax.swing.JViewport, javax.swing.event.ChangeListener>>()

    private val baseFont: Font = (UIUtil.getLabelFont() ?: Font("SansSerif", Font.PLAIN, 12)).deriveFont(12.5f)
    private val boldFont: Font = baseFont.deriveFont(Font.BOLD)
    private val smallFont: Font = baseFont.deriveFont(10.5f)
    private var fm: FontMetrics = getFontMetrics(baseFont)
    private var fmSmall: FontMetrics = getFontMetrics(smallFont)

    private val panelBg: Color = UIUtil.getPanelBackground()
    private val dark: Boolean = (panelBg.red + panelBg.green + panelBg.blue) < 3 * 110
    private val textMain: Color = if (dark) Color(0xE3E6EA) else Color(0x2C2F33)
    private val textDim: Color = if (dark) Color(0x9AA3AD) else Color(0x6C7480)
    private val shadow: Color = if (dark) Color(0, 0, 0, 90) else Color(40, 44, 52, 40)

    private inner class DragState(
        val note: Note,
        val editor: Editor,
        val pressPoint: Point,
        val cardPoint: Point,
    ) {
        var moved = false
    }

    private inner class CardView(
        val note: Note,
        val editor: Editor,
        val rect: Rectangle,
        val anchor: Point,
        val bodyLines: List<String>,
        val expanded: Boolean,
    )

    init {
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        serviceListener = service.addListener {
            fm = getFontMetrics(baseFont)
            fmSmall = getFontMetrics(smallFont)
            repaint()
        }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = onPressed(e)
            override fun mouseReleased(e: MouseEvent) = onReleased(e)
            override fun mouseClicked(e: MouseEvent) = onClicked(e)
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) = onDragged(e)
            override fun mouseMoved(e: MouseEvent) = onMoved(e)
        })

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    refresh()
                }
            },
        )
    }

    // ------------------------------------------------------------ 挂载

    fun refresh() {
        val editors = currentEditors()
        for (te in editors) attach(te.editor)
        syncBounds()
        repaint()
    }

    /** 窗口尺寸变化、或挂载时窗口还没布局完，都会靠这里自愈。 */
    private fun syncBounds() {
        val p = parent ?: return
        if (p.width > 0 && p.height > 0 && (bounds.width != p.width || bounds.height != p.height)) {
            setBounds(0, 0, p.width, p.height)
        }
    }

    fun resizeToParent() {
        val parent = parent ?: return
        setBounds(0, 0, parent.width, parent.height)
        revalidate()
        repaint()
    }

    private fun attach(editor: Editor) {
        if (!attached.add(editor)) return
        val feeder = Disposable { attached.remove(editor) }
        Disposer.register(this, feeder)
        editor.scrollingModel.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(e: VisibleAreaEvent) = repaint()
        }, this)
        // 兜底：直接盯视口变化。编辑器换行高、滚动、改变宽度都会走这里。
        val vp = SwingUtilities.getAncestorOfClass(javax.swing.JViewport::class.java, editor.contentComponent) as? javax.swing.JViewport
        if (vp != null && viewportHooks.none { it.first === vp }) {
            val hook = javax.swing.event.ChangeListener { repaint() }
            vp.addChangeListener(hook)
            viewportHooks.add(vp to hook)
        }
        editor.contentComponent.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = repaint()
        })
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                service.scheduleResolve(editor)
                repaint()
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = repaint()
        }, this)
    }

    private fun currentEditors(): List<TextEditor> =
        FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<TextEditor>()

    // ------------------------------------------------------------ 几何

    private fun views(): List<CardView> {
        if (!service.enabled || !service.isAvailable() || width <= 0 || height <= 0) return emptyList()
        val out = ArrayList<CardView>()
        val editors = runCatching { currentEditors() }.getOrElse { emptyList() }
        for (te in editors) {
            runCatching { collect(te.editor, out) }.onFailure { warn(it) }
        }
        return out
    }

    private fun collect(editor: Editor, out: MutableList<CardView>) {
        if (!editor.contentComponent.isShowing) return
        val notes = service.notesFor(editor)
        if (notes.isEmpty()) return
        val content = editor.contentComponent
        val origin = SwingUtilities.convertPoint(content, 0, 0, this)
        val doc = editor.document
        val lineCount = doc.lineCount
        if (lineCount <= 0) return
        val lineHeight = editor.lineHeight.coerceAtLeast(8)
        val scrollY = runCatching { editor.scrollingModel.verticalScrollOffset }.getOrDefault(0)
        val visible = content.visibleRect
        val viewTop = if (visible.height > 0) visible.y else scrollY
        val viewHeight = if (visible.height > 0) visible.height else (content.parent?.height ?: content.height)
        val clipTop = origin.y + viewTop + 4
        val clipBottom = origin.y + viewTop + viewHeight - 4
        for (note in notes) {
            val effective = preview?.takeIf { it.id == note.id } ?: note
            val line = effective.line.coerceIn(0, lineCount - 1)
            val lineTop = editor.visualPositionToXY(VisualPosition(line, 0)).y
            if (lineTop + lineHeight < viewTop || lineTop > viewTop + viewHeight) continue
            val endOffset = doc.getLineEndOffset(line)
            val endX = runCatching { editor.offsetToXY(endOffset).x }.getOrDefault(0)
            val anchor = Point(origin.x + endX, origin.y + lineTop + lineHeight / 2)
            val layout = layoutOf(effective)
            var x = anchor.x + effective.dx
            var y = anchor.y + effective.dy
            x = x.coerceIn(4, (width - layout.width - 4).coerceAtLeast(4))
            val top = clipTop
            val bottom = clipBottom - layout.height
            y = if (bottom >= top) y.coerceIn(top, bottom) else top
            out.add(
                CardView(
                    note = effective,
                    editor = editor,
                    rect = Rectangle(x, y, layout.width, layout.height),
                    anchor = anchor,
                    bodyLines = layout.lines,
                    expanded = !effective.collapsed,
                ),
            )
        }
    }

    private fun warn(t: Throwable) {
        if (warned) return
        warned = true
        com.intellij.openapi.diagnostic.Logger.getInstance(NoteOverlay::class.java)
            .warn("注释栏绘制失败（只报一次）", t)
    }

    private class Layout(val width: Int, val height: Int, val lines: List<String>)

    private fun layoutOf(note: Note): Layout {
        val textWidth = CARD_W - PAD * 2 - 4
        val body = if (note.collapsed) {
            listOf(ellipsize(note.text.lineSequence().firstOrNull() ?: ""))
        } else {
            wrap(note.text.ifBlank { "（空注释，双击写点什么）" }, textWidth, MAX_BODY_LINES)
        }
        val bodyHeight = body.size * LINE_H
        val height = HEADER_H + bodyHeight + if (note.collapsed) 10 else 12
        return Layout(CARD_W, height.coerceAtLeast(HEADER_H + 12), body)
    }

    private fun ellipsize(text: String): String {
        if (fm.stringWidth(text) <= CARD_W - PAD * 2 - 30) return text
        var s = text
        while (s.length > 1 && fm.stringWidth("$s…") > CARD_W - PAD * 2 - 30) s = s.dropLast(1)
        return "$s…"
    }

    private fun wrap(text: String, maxWidth: Int, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        val rawLines = text.split('\n')
        for ((index, raw) in rawLines.withIndex()) {
            if (out.size >= maxLines) return out
            if (raw.isEmpty()) {
                out.add("")
                continue
            }
            val sb = StringBuilder()
            var lastBreak = -1
            for (ch in raw) {
                sb.append(ch)
                if (ch == ' ') lastBreak = sb.length
                if (fm.stringWidth(sb.toString()) > maxWidth) {
                    val cut = if (lastBreak > sb.length / 2) lastBreak else sb.length - 1
                    val head = sb.substring(0, cut).trimEnd()
                    val tail = sb.substring(cut).trimStart()
                    if (out.size >= maxLines - 1 && (index < rawLines.lastIndex || tail.isNotEmpty())) {
                        out.add(withEllipsis(head, maxWidth))
                        return out
                    }
                    out.add(head)
                    sb.setLength(0)
                    sb.append(tail)
                    lastBreak = -1
                }
            }
            if (sb.isNotEmpty()) {
                if (out.size >= maxLines) {
                    out[out.size - 1] = withEllipsis(out[out.size - 1], maxWidth)
                    return out
                }
                out.add(sb.toString())
            }
        }
        if (out.isEmpty()) out.add("")
        return out.take(maxLines)
    }

    private fun withEllipsis(text: String, maxWidth: Int): String {
        var s = text
        while (s.length > 1 && fm.stringWidth("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    // ------------------------------------------------------------ 绘制

    override fun paintComponent(g: Graphics) {
        syncBounds()
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val list = views()
            if (list.isEmpty()) return
            for (v in list) drawLeader(g2, v)
            for (v in list) drawCard(g2, v)
        } catch (t: Throwable) {
            warn(t)
        } finally {
            g2.dispose()
        }
    }

    private fun accent(note: Note): Color = Color(Palette.byId(note.color).rgb)

    private fun cardFill(note: Note): Color = mix(panelBg, accent(note), if (dark) 0.20f else 0.09f)

    private fun cardBorder(note: Note): Color = mix(panelBg, accent(note), if (dark) 0.62f else 0.45f)

    private fun drawLeader(g2: Graphics2D, v: CardView) {
        val a = accent(v.note)
        val fromX: Int
        val fromY = v.rect.y + HEADER_H / 2 + 4
        fromX = if (v.anchor.x < v.rect.x) v.rect.x else v.rect.x + v.rect.width
        val from = Point(fromX, fromY)
        val path = Path2D.Float()
        path.moveTo(from.x.toFloat(), from.y.toFloat())
        val midX = (from.x + v.anchor.x) / 2f
        path.quadTo(midX, from.y.toFloat(), midX, ((from.y + v.anchor.y) / 2f).toFloat())
        path.quadTo(midX, v.anchor.y.toFloat(), v.anchor.x.toFloat(), v.anchor.y.toFloat())
        g2.color = Color(a.red, a.green, a.blue, if (v.note.detached) 90 else 165)
        g2.stroke = if (v.note.detached) {
            BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(4f, 4f), 0f)
        } else {
            BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        g2.draw(path)
        g2.stroke = BasicStroke(1f)
        g2.color = Color(a.red, a.green, a.blue, if (v.note.detached) 120 else 235)
        g2.fillOval(v.anchor.x - 3, v.anchor.y - 3, 7, 7)
        g2.color = Color(a.red, a.green, a.blue, 60)
        g2.drawOval(v.anchor.x - 6, v.anchor.y - 6, 13, 13)
    }

    private fun drawCard(g2: Graphics2D, v: CardView) {
        val note = v.note
        val a = accent(note)
        val r = v.rect
        val hovered = hoverId == note.id || drag?.note?.id == note.id

        // 阴影
        g2.color = shadow
        g2.fillRoundRect(r.x + 1, r.y + 2, r.width, r.height, RADIUS, RADIUS)

        val shape = RoundRectangle2D.Float(
            r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat(),
            RADIUS.toFloat(), RADIUS.toFloat(),
        )
        g2.color = if (hovered) mix(cardFill(note), accent(note), 0.08f) else cardFill(note)
        g2.fill(shape)

        val oldClip = g2.clip
        g2.clip(shape)
        g2.color = a
        g2.fillRect(r.x, r.y, 4, r.height)
        g2.clip = oldClip

        g2.color = if (hovered) a else cardBorder(note)
        g2.stroke = BasicStroke(if (hovered) 1.6f else 1f)
        g2.draw(shape)
        g2.stroke = BasicStroke(1f)

        // 折叠箭头
        val cy = r.y + HEADER_H / 2 + 2
        val cx = r.x + PAD + 3
        val tri = Path2D.Float()
        if (v.expanded) {
            tri.moveTo((cx - 4).toFloat(), (cy - 2).toFloat())
            tri.lineTo((cx + 4).toFloat(), (cy - 2).toFloat())
            tri.lineTo(cx.toFloat(), (cy + 3).toFloat())
        } else {
            tri.moveTo((cx - 2).toFloat(), (cy - 4).toFloat())
            tri.lineTo((cx - 2).toFloat(), (cy + 4).toFloat())
            tri.lineTo((cx + 4).toFloat(), cy.toFloat())
        }
        tri.closePath()
        g2.color = if (hovered) a else textDim
        g2.fill(tri)

        // 行号徽标
        g2.font = smallFont
        g2.color = textDim
        val badge = "L" + (note.line + 1)
        val badgeX = cx + 10
        g2.drawString(badge, badgeX, r.y + HEADER_H / 2 + fmSmall.ascent / 2 - 1)
        val badgeW = fmSmall.stringWidth(badge)

        if (note.detached) {
            g2.color = Color(0xF2_6D_6D)
            g2.drawString("锚点丢失", badgeX + badgeW + 10, r.y + HEADER_H / 2 + fmSmall.ascent / 2 - 1)
        }

        // 正文
        g2.font = if (v.expanded) baseFont else smallFont
        val metrics = if (v.expanded) fm else fmSmall
        g2.color = if (note.text.isBlank()) textDim else textMain
        var baseline = r.y + HEADER_H + metrics.ascent + 3
        for (line in v.bodyLines) {
            g2.drawString(line, r.x + PAD + 4, baseline)
            baseline += if (v.expanded) LINE_H else metrics.height
        }
    }

    // ------------------------------------------------------------ 交互

    // 平台有时会从后台线程问 contains()（布局/命中探测），而 visualPositionToXY 只允许 EDT，
    // 非 EDT 一律回 false，避免刷异常。
    override fun contains(x: Int, y: Int): Boolean {
        if (!SwingUtilities.isEventDispatchThread()) return false
        return runCatching { hitTest(x, y) != null }.getOrDefault(false)
    }

    private fun hitTest(x: Int, y: Int): CardView? {
        val list = views()
        for (i in list.indices.reversed()) {
            val v = list[i]
            if (v.rect.contains(x, y)) return v
        }
        return null
    }

    private fun onPressed(e: MouseEvent) {
        val view = hitTest(e.x, e.y) ?: return
        if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
            showMenu(view, e.x, e.y)
            return
        }
        val note = view.note
        // 折叠按钮
        val chevron = Rectangle(view.rect.x, view.rect.y, PAD + 22, HEADER_H)
        if (chevron.contains(e.x, e.y)) {
            val updated = note.copy(collapsed = !note.collapsed, updatedAt = System.currentTimeMillis())
            service.updateNote(view.editor, updated)
            return
        }
        drag = DragState(note, view.editor, e.point, Point(view.rect.x, view.rect.y))
    }

    private fun onDragged(e: MouseEvent) {
        val d = drag ?: return
        val delta = Point(e.x - d.pressPoint.x, e.y - d.pressPoint.y)
        if (!d.moved && (kotlin.math.abs(delta.x) > 2 || kotlin.math.abs(delta.y) > 2)) d.moved = true
        if (!d.moved) return
        val next = d.note.copy(
            dx = d.note.dx + delta.x,
            dy = d.note.dy + delta.y,
            updatedAt = System.currentTimeMillis(),
        )
        preview = next
        repaint()
    }

    private fun onReleased(e: MouseEvent) {
        val d = drag ?: return
        drag = null
        val next = preview
        preview = null
        if (d.moved && next != null) {
            service.updateNote(d.editor, next)
        } else if (!d.moved) {
            // 单击 = 跳到锚点行
            val editor = d.editor
            val line = d.note.line.coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
            editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(line, 0))
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
            editor.contentComponent.requestFocusInWindow()
        }
        repaint()
    }

    private fun onClicked(e: MouseEvent) {
        if (e.clickCount < 2 || SwingUtilities.isRightMouseButton(e)) return
        val view = hitTest(e.x, e.y) ?: return
        openEditor(view)
    }

    private fun openEditor(view: CardView) {
        val editor = view.editor
        val local = SwingUtilities.convertPoint(
            this,
            Point(view.rect.x, view.rect.y + view.rect.height + 6),
            editor.contentComponent,
        )
        NotePopup.open(
            project = project,
            editor = editor,
            note = view.note,
            anchorPoint = local,
            onSave = { updated -> service.updateNote(editor, updated) },
            onDelete = { service.deleteNote(editor, view.note.id) },
        )
    }

    private fun onMoved(e: MouseEvent) {
        val view = hitTest(e.x, e.y)
        val id = view?.note?.id
        if (id != hoverId) {
            hoverId = id
            cursor = if (view == null) {
                Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            } else {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            repaint()
        }
    }

    private fun showMenu(view: CardView, x: Int, y: Int) {
        val menu = JPopupMenu()
        val note = view.note

        val edit = javax.swing.JMenuItem("编辑文字…")
        edit.addActionListener { openEditor(view) }
        menu.add(edit)

        val colors = javax.swing.JMenu("换个颜色")
        for (c in Palette.colors) {
            val item = javax.swing.JMenuItem(c.label + if (c.id == note.color) "  ✓" else "", ColorDot(c))
            item.addActionListener {
                service.updateNote(view.editor, note.copy(color = c.id, updatedAt = System.currentTimeMillis()))
            }
            colors.add(item)
        }
        menu.add(colors)

        val collapse = javax.swing.JMenuItem(if (note.collapsed) "展开" else "收起")
        collapse.addActionListener {
            service.updateNote(view.editor, note.copy(collapsed = !note.collapsed, updatedAt = System.currentTimeMillis()))
        }
        menu.add(collapse)

        val jump = javax.swing.JMenuItem("跳到 L" + (note.line + 1))
        jump.addActionListener {
            val editor = view.editor
            val line = note.line.coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
            editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(line, 0))
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
        menu.add(jump)

        menu.addSeparator()
        val del = javax.swing.JMenuItem("删除注释栏")
        del.addActionListener {
            val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "删除 L" + (note.line + 1) + " 的注释卡？\n这会改写 .loji 里对应的 json 文件。",
                "删除注释栏",
                "删除",
                "取消",
                null,
            )
            if (answer == com.intellij.openapi.ui.Messages.YES) {
                service.deleteNote(view.editor, note.id)
            }
        }
        menu.add(del)

        menu.show(this, x, y)
    }

    // ------------------------------------------------------------ 工具

    private fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * k).toInt(),
            (a.green + (b.green - a.green) * k).toInt(),
            (a.blue + (b.blue - a.blue) * k).toInt(),
        )
    }

    private class ColorDot(private val c: NoteColor) : javax.swing.Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12
        override fun paintIcon(comp: java.awt.Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = g?.create() as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(c.rgb)
            g2.fillOval(x, y, 12, 12)
            g2.color = JBColor.border()
            g2.drawOval(x, y, 12, 12)
            g2.dispose()
        }
    }

    fun preferredOverlaySize(): Dimension = Dimension(parent?.width ?: 0, parent?.height ?: 0)

    override fun dispose() {
        parent?.remove(this)
        for ((vp, hook) in viewportHooks) runCatching { vp.removeChangeListener(hook) }
        viewportHooks.clear()
        attached.clear()
    }
}
