package edu.t07.coursemate.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

/** Real IntelliJ light fixture/editor/action registration. No network, no paid API calls. */
public final class AskCourseMateActionTest extends BasePlatformTestCase {
    public void testRegisteredActionAndEditorPopupDescriptor() throws Exception {
        AnAction action = ActionManager.getInstance().getAction("edu.t07.coursemate.AskSelectedCode");
        assertNotNull(action);
        assertEquals(AskCourseMateAction.class.getName(), action.getClass().getName());
        assertEquals("Ask CourseMate AI", action.getTemplatePresentation().getText());
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile());
        var actionNode = (org.w3c.dom.Element) xml.getElementsByTagName("action").item(0);
        assertEquals("edu.t07.coursemate.AskSelectedCode", actionNode.getAttribute("id"));
        var group = (org.w3c.dom.Element) actionNode.getElementsByTagName("add-to-group").item(0);
        assertEquals("EditorPopupMenu", group.getAttribute("group-id"));
    }

    public void testActualJavaSelectionIsCapturedExactlyWithoutReadingOutsideSelection() {
        myFixture.configureByText("Example.java", "// outside-before\nclass Example {\n<selection>  int value = 1;\n  int next = value + 1;</selection>\n}\n// outside-after");
        String before = myFixture.getEditor().getDocument().getText();
        AskCourseMateAction action = new AskCourseMateAction();
        AnActionEvent event = event(action);
        action.update(event);
        assertTrue(event.getPresentation().isEnabled());
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
        assertEquals("  int value = 1;\n  int next = value + 1;", EditorCodeSelection.read(event).orElseThrow());
        assertEquals(before, myFixture.getEditor().getDocument().getText());
    }

    public void testNoSelectionDisablesActionAndInvalidInvocationDoesNothing() {
        myFixture.configureByText("Example.java", "class Example {} ");
        AskCourseMateAction action = new AskCourseMateAction();
        AnActionEvent event = event(action);
        String before = myFixture.getEditor().getDocument().getText();
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        assertTrue(EditorCodeSelection.read(event).isEmpty());
        action.actionPerformed(event); // Must return before tool-window creation or any possible request.
        assertEquals(before, myFixture.getEditor().getDocument().getText());
    }

    public void testWhitespaceSelectionAndNonJavaFileAreDisabled() {
        myFixture.configureByText("Example.java", "class Example {<selection>  \n  </selection>}");
        AskCourseMateAction action = new AskCourseMateAction();
        AnActionEvent event = event(action);
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        myFixture.configureByText("notes.txt", "<selection>not Java source</selection>");
        event = event(action);
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
    }

    public void testNoEditorOrProjectIsDisabled() {
        AskCourseMateAction action = new AskCourseMateAction();
        var event = AnActionEvent.createEvent(action, SimpleDataContext.EMPTY_CONTEXT,
                action.getTemplatePresentation().clone(), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null);
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        action.actionPerformed(event);
    }

    private AnActionEvent event(AnAction action) {
        var context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.VIRTUAL_FILE, myFixture.getFile().getVirtualFile()).build();
        return AnActionEvent.createEvent(action, context, action.getTemplatePresentation().clone(),
                ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null);
    }
}
