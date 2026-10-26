package edu.t07.coursemate.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import edu.t07.coursemate.ui.CourseMateChatPanel;
import org.jetbrains.annotations.NotNull;

/** Stateless action: no retained Project, Editor, or selected source code. Never sends a request. */
public final class AskCourseMateAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(EditorCodeSelection.read(event).isPresent());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        String code = EditorCodeSelection.read(event).orElse(null);
        Project project = event.getProject();
        if (code == null || project == null) return; // Recheck selection after menu update; never send empty code.
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("CourseMate AI");
        if (window == null) {
            Messages.showInfoMessage(project, "CourseMate AI 工具窗口尚不可用，请稍后重试。", "CourseMate AI");
            return;
        }
        var snapshot = JavaSelectionContext.capture(event, code);
        window.activate(() -> {
            if (project.isDisposed()) return;
            for (var content : window.getContentManager().getContents()) {
                if (content.getComponent() instanceof CourseMateChatPanel panel) {
                    if (!panel.prepareCodeQuestion(snapshot)) {
                        Messages.showInfoMessage(project, "当前请求尚未结束，请等待后再次选择代码提问。", "CourseMate AI");
                    }
                    return;
                }
            }
            Messages.showInfoMessage(project, "工具窗口仍在初始化，请再次选择代码提问。", "CourseMate AI");
        });
    }
}
