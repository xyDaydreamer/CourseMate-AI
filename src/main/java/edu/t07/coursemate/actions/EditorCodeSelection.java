package edu.t07.coursemate.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Optional;

/** Captures only the active Java editor selection, never other document text or project files. */
public final class EditorCodeSelection {
    private EditorCodeSelection() { }

    public static Optional<String> read(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || project.isDisposed() || editor == null || editor.isDisposed()
                || file == null || !"java".equalsIgnoreCase(file.getExtension())
                || !editor.getSelectionModel().hasSelection()) return Optional.empty();
        String selected = editor.getSelectionModel().getSelectedText();
        return selected == null || selected.isBlank() ? Optional.empty() : Optional.of(selected);
    }
}
