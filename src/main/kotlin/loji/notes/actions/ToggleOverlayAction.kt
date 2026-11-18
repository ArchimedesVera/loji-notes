package loji.notes.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import loji.notes.LojiService
import loji.notes.OverlayHost

/** 一次性收起 / 展开所有注释卡。 */
class ToggleOverlayAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = LojiService.get(project)
        e.presentation.isEnabledAndVisible = service.isAvailable()
        e.presentation.text = if (service.enabled) "注释栏：隐藏" else "注释栏：显示"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = LojiService.get(project)
        service.enabled = !service.enabled
        OverlayHost.get(project).refreshAll()
    }
}
