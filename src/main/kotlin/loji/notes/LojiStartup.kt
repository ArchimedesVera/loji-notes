package loji.notes

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 项目打开后把注释画布挂上去。 */
class LojiStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        OverlayHost.get(project).install()
    }
}
