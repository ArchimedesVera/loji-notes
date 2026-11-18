package loji.notes.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import loji.notes.model.Note
import loji.notes.model.Palette
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder

/** 双击卡片 / 新建注释后弹出的编辑框。 */
object NotePopup {

    fun open(
        project: Project,
        editor: Editor,
        note: Note,
        anchorPoint: Point? = null,
        onSave: (Note) -> Unit,
        onDelete: (() -> Unit)? = null,
    ) {
        val panel = Panel(note, onDelete != null)
        var popup: JBPopup? = null

        val close: () -> Unit = { popup?.cancel() }
        val save = {
            val text = panel.textArea.text
            if (text != note.text || panel.colorId != note.color) {
                onSave(note.copy(text = text, color = panel.colorId, updatedAt = System.currentTimeMillis()))
            }
            close()
        }

        panel.onConfirm = save
        panel.onDelete = {
            onDelete?.invoke()
            close()
        }

        panel.textArea.registerKeyboardAction(
            { save() },
            KeyStroke.getKeyStroke("ctrl ENTER"),
            JTextArea.WHEN_FOCUSED,
        )

        val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.textArea)
            .setTitle("注释 · L" + (note.line + 1))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setDimensionServiceKey(project, "loji.notes.popup", false)

        popup = builder.createPopup()
        val anchor = anchorPoint ?: editor.visualPositionToXY(editor.caretModel.visualPosition)
        popup?.show(RelativePoint(editor.contentComponent, anchor))
    }

    private class Panel(note: Note, withDelete: Boolean) : JPanel(BorderLayout()) {

        val textArea = JTextArea(note.text, 4, 32)
        var colorId: String = note.color
            private set
        var onConfirm: () -> Unit = {}
        var onDelete: () -> Unit = {}

        private val swatches = ArrayList<Swatch>()

        init {
            border = EmptyBorder(8, 10, 8, 10)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textArea.font = textArea.font.deriveFont(13f)
            textArea.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            val scroll = JScrollPane(textArea)
            scroll.border = JBUI.Borders.customLine(borderColor(), 1)
            scroll.preferredSize = Dimension(360, 110)
            add(scroll, BorderLayout.CENTER)

            val south = JPanel()
            south.layout = BoxLayout(south, BoxLayout.X_AXIS)
            south.border = EmptyBorder(8, 0, 0, 0)

            val colors = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            colors.isOpaque = false
            for (c in Palette.colors) {
                val sw = Swatch(c.id, Color(c.rgb), c.id == note.color) { picked ->
                    colorId = picked
                    swatches.forEach { it.repaint() }
                }
                swatches.add(sw)
                colors.add(sw)
            }
            south.add(colors)
            south.add(Box.createHorizontalGlue())

            if (withDelete) {
                val del = JButton("删除")
                del.addActionListener { onDelete() }
                south.add(del)
                south.add(Box.createHorizontalStrut(6))
            }
            val ok = JButton("保存")
            ok.addActionListener { onConfirm() }
            south.add(ok)

            add(south, BorderLayout.SOUTH)
            preferredSize = Dimension(380, 190)
        }

        private fun borderColor(): Color = com.intellij.ui.JBColor(0xD5D9DE, 0x4A4F57)

        override fun addNotify() {
            super.addNotify()
            textArea.requestFocusInWindow()
            textArea.caretPosition = textArea.text.length
        }
    }

    private class Swatch(
        private val id: String,
        private val color: Color,
        initialSelected: Boolean,
        private val onPick: (String) -> Unit,
    ) : JPanel() {
        private var selected = initialSelected

        init {
            preferredSize = Dimension(18, 18)
            isOpaque = false
            toolTipText = Palette.byId(id).label
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    selected = true
                    onPick(id)
                }
            })
        }

        override fun paint(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(1, 1, 15, 15)
            g2.color = if (selected) com.intellij.ui.JBColor(0x2C2F33, 0xE3E6EA) else color.darker()
            g2.stroke = java.awt.BasicStroke(if (selected) 2f else 1f)
            g2.drawOval(1, 1, 15, 15)
            g2.dispose()
        }
    }
}
