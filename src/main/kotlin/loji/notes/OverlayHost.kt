package loji.notes

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import loji.notes.ui.NoteOverlay
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.WeakHashMap
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.JRootPane

/**
 * 把透明注释画布装到 IDE 主窗口的 layered pane 上（POPUP 层，编辑器之上）。
 *
 * 全窗口只装一块画布，画布内部遍历当前所有可见编辑器，所以分屏也能各自显示自己的注释。
 */
@Service(Service.Level.PROJECT)
class OverlayHost(private val project: Project) : Disposable {

    companion object {
        fun get(project: Project): OverlayHost = project.service()
    }

    private val overlays = WeakHashMap<JRootPane, NoteOverlay>()

    init {
        // 主窗口可能比插件启动活动更晚就绪，靠编辑器事件兜底再挂一次。
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    install()
                }
            },
        )
    }

    fun install() {
        val frame: JFrame = WindowManager.getInstance().getFrame(project) ?: return
        val rootPane: JRootPane = frame.rootPane ?: return
        overlays[rootPane]?.let {
            it.refresh()
            return
        }
        val layered: JLayeredPane = rootPane.layeredPane ?: return

        val overlay = NoteOverlay(project)
        overlay.isOpaque = false
        overlay.bounds = Rectangle(0, 0, layered.width, layered.height)
        // 注意：JBLayeredPane.add(Component, int) 的 int 是「子组件索引」，不是层级。
        // 必须先用 add(Component) 再加 setLayer(...)，否则画布会被塞到编辑器下面。
        layered.add(overlay)
        layered.setLayer(overlay, JLayeredPane.POPUP_LAYER)
        layered.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                overlay.bounds = Rectangle(0, 0, layered.width, layered.height)
                overlay.repaint()
            }
        })
        Disposer.register(project, overlay)
        overlays[rootPane] = overlay
        overlay.refresh()
    }

    fun refreshAll() {
        overlays.values.forEach { it.refresh() }
    }

    override fun dispose() {
        for ((root, overlay) in overlays) {
            root.layeredPane?.remove(overlay)
        }
        overlays.clear()
    }
}
