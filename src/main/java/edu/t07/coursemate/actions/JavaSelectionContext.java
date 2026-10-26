package edu.t07.coursemate.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.psi.*;
import edu.t07.coursemate.chat.ChatSession;
import edu.t07.coursemate.rag.CodeRetrievalQuery;
import java.util.*;

/** Parses only the explicitly selected text. Never resolves calls or reads surrounding project source. */
public final class JavaSelectionContext {
    private JavaSelectionContext() { }
    public static ChatSession.Code capture(AnActionEvent event, String selected) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        int start = editor.getSelectionModel().getSelectionStart(), end = editor.getSelectionModel().getSelectionEnd();
        int first = editor.getDocument().getLineNumber(start) + 1;
        int last = editor.getDocument().getLineNumber(Math.max(start, end - 1)) + 1;
        List<String> features = selected.length() > ChatSession.MAX_CODE ? List.of() : ReadAction.compute(() -> features(event.getProject(), selected));
        return new ChatSession.Code(selected, file == null ? "" : file.getName(), first, last,
                editor.getDocument().getModificationStamp(), features);
    }
    public static List<String> features(com.intellij.openapi.project.Project project, String selected) {
        Set<String> result = new LinkedHashSet<>();
        // A fragment wrapper allows statements/methods to be parsed without importing unselected code.
        for (String source : List.of(selected, "class Selection { " + selected + " }", "class Selection { void fragment() { " + selected + " } }")) {
            PsiFile parsed = PsiFileFactory.getInstance(project).createFileFromText("Selection.java", JavaFileType.INSTANCE, source);
            parsed.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
                    String name = reference.getReferenceName();
                    if (name != null && Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "List", "ArrayList", "LinkedList", "Set", "HashSet", "Iterator").contains(name)) result.add(name);
                    if (reference.getParameterList() != null && reference.getParameterList().getTypeParameterElements().length > 0) result.add("generics");
                    super.visitReferenceElement(reference);
                }
                @Override public void visitMethodCallExpression(PsiMethodCallExpression call) {
                    String name = call.getMethodExpression().getReferenceName();
                    if (name != null && Set.of("merge", "getOrDefault", "entrySet", "iterator", "assertEquals", "assertThrows", "assertTrue").contains(name)) result.add(name);
                    super.visitMethodCallExpression(call);
                }
                @Override public void visitClass(PsiClass type) {
                    if (type.getExtendsListTypes().length > 0 || type.getImplementsListTypes().length > 0) result.add("inheritance");
                    super.visitClass(type);
                }
            });
        }
        // Lexer fallback also covers incomplete declarations; it excludes comments/literals and arbitrary method names.
        String lexical = CodeRetrievalQuery.build("Explain this code.", selected).replace("Explain this code.", "").strip();
        if (!lexical.isEmpty()) result.addAll(List.of(lexical.split("\\s+")));
        return List.copyOf(result);
    }
}
