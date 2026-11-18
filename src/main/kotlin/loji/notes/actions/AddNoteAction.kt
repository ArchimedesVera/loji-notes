package loji.notes.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import loji.notes.LojiService
import loji.notes.OverlayHost
import loji.notes.ui.NotePopup

/** 在光标行新建一张注释卡，并立刻打开编辑框。 */
class AddNoteAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.document != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val service = LojiService.get(project)
        if (!service.isAvailable()) return
        OverlayHost.get(project).install()
        val line = editor.caretModel.logicalPosition.line
        val note = service.addNote(editor, line) ?: return
        NotePopup.open(
            project = project,
            editor = editor,
            note = note,
            onSave = { service.updateNote(editor, it) },
            onDelete = { service.deleteNote(editor, note.id) },
        )
    }
}
