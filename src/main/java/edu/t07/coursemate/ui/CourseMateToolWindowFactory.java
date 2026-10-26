package edu.t07.coursemate.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Registers a separate chat view for each open project. */
public final class CourseMateToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // Auto-show only for launcher sessions; normal plugin installations keep their existing layout.
        if (!Boolean.getBoolean("coursemate.openToolWindowOnStart")) return;
        Project project = toolWindow.getProject();
        DemoProjectSdk.configure(project);
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            if (!project.isDisposed() && !toolWindow.isDisposed()) {
                toolWindow.show(() -> Logger.getInstance(CourseMateToolWindowFactory.class)
                        .info("CourseMate startup tool window shown"));
            }
        });
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CourseMateChatPanel panel = new CourseMateChatPanel(project.getBasePath());
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
