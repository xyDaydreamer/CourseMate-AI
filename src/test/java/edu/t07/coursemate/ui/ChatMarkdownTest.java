package edu.t07.coursemate.ui;

import org.junit.jupiter.api.Test;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.rag.CourseChunk;
import edu.t07.coursemate.rag.RagAnswerService;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMarkdownTest {
    @Test
    void headingsAndFencesBecomeStructureWithoutMarkerCharacters() {
        String html = ChatMarkdown.toHtml("""
                ### 继承
                子类通过 **extends** 复用父类。

                ```java
                class Child extends Parent {}
                ```

                使用 `extends` 声明继承。
                """);
        assertTrue(html.contains("<h3>继承</h3>"));
        assertTrue(html.contains("<b>extends</b>"));
        assertTrue(html.contains("<pre>class Child extends Parent {}</pre>"));
        assertTrue(html.contains("<code>extends</code>"));
        assertFalse(html.contains("###"));
        assertFalse(html.contains("```"));
        assertFalse(html.contains("**"));
    }

    @Test
    void listsStayReadableAndMarkupIsEscaped() {
        String html = ChatMarkdown.toHtml("""
                - 第一点
                - 第二点

                1. 先写测试
                2. 再写实现

                <script>alert(1)</script>
                """);
        assertTrue(html.contains("<ul><li>第一点</li><li>第二点</li></ul>"));
        assertTrue(html.contains("<ol><li>先写测试</li><li>再写实现</li></ol>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    void realLectureExcerptsKeepIndentationNewlinesAndLiteralMarkdown() throws Exception {
        String excerpt = "Map example:\n  if (ok) { // **keep literal**\n    counts.merge(\"java\", 1, Integer::sum);\n  }\n```java\n  nested();\n```";
        String answer = RagAnswerService.renderGrounded("{\"course_answer\":\"Count values.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}",
                List.of(new CourseChunk("Lec-3.2-Test.pdf", 15, excerpt)), ReplyLanguage.EN);
        String html = ChatMarkdown.toHtml(answer);
        assertTrue(html.contains("<pre>" + excerpt + "</pre>"));
        assertFalse(html.contains("<b>keep literal</b>"));
        assertTrue(html.contains("Lec-3.2-Test.pdf — PDF page 15"));
    }

    @Test
    void citationLinesRemainVisible() {
        String html = ChatMarkdown.toHtml("[S1] Lec-2-OOP-with-Java.pdf — PDF 第 12 页\n");
        assertTrue(html.contains("[S1] Lec-2-OOP-with-Java.pdf — PDF 第 12 页"));
        assertFalse(html.contains("###"));
    }
}
