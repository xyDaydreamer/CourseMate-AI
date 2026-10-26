package edu.t07.coursemate.ui;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekClient;
import edu.t07.coursemate.ai.DeepSeekException;
import edu.t07.coursemate.rag.CourseKnowledgeBase;
import edu.t07.coursemate.rag.RagAnswerService;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Chat UI. All HTTP work runs outside the EDT; completion updates run on the EDT. */
public final class CourseMateChatPanel extends JPanel implements Disposable {
    private final JEditorPane conversation = new JEditorPane();
    private final StringBuilder chatLog = new StringBuilder();
    private final JScrollPane conversationScroll;
    private final JBTextArea questionInput = new JBTextArea(3, 20);
    private final JButton sendButton = new JButton("Send");
    private final JBLabel status = new JBLabel("Ready · DeepSeek / " + DeepSeekClient.MODEL);
    private final DeepSeekClient client = new DeepSeekClient();
    private final JComboBox<String> languageBox = new JComboBox<>(new String[]{"English", "中文"});
    private final JButton rebuildButton = new JButton("Rebuild index");
    private final JBLabel inputLabel = new JBLabel();
    private final JBLabel codeTitle = new JBLabel();
    private ReplyLanguage replyLanguage = ReplyLanguage.EN;
    private CourseKnowledgeBase.Report lastReport;
    private IndexNote indexNote = IndexNote.NONE;
    private final JBTextArea codePreview = new JBTextArea(4, 20);
    private final JPanel codePanel = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
    private final JButton clearCodeButton = new JButton("取消代码上下文");
    private String selectedCode = "";
    private String priorQuestion = "";
    private String priorAnswer = "";
    private boolean answering;
    private final CourseKnowledgeBase knowledge;
    private final RagAnswerService answers;
    private SwingWorker<?, Void> worker;
    private boolean busy;
    private volatile boolean disposed;

    public CourseMateChatPanel(String projectBasePath) {
        super(new BorderLayout(0, JBUI.scale(8)));
        knowledge = new CourseKnowledgeBase(CourseKnowledgeBase.locate(projectBasePath));
        answers = new RagAnswerService(knowledge, client::ask);
        setBorder(JBUI.Borders.empty(10));

        JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
        header.add(status, BorderLayout.CENTER);
        JPanel headerActions = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        headerActions.add(languageBox, BorderLayout.WEST);
        headerActions.add(rebuildButton, BorderLayout.EAST);
        header.add(headerActions, BorderLayout.EAST);
        rebuildButton.addActionListener(event -> startIndex(true));
        add(header, BorderLayout.NORTH);

        conversation.setContentType("text/html");
        conversation.setEditable(false);
        conversation.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (UIManager.getFont("TextArea.font") != null) conversation.setFont(UIManager.getFont("TextArea.font"));
        conversation.getAccessibleContext().setAccessibleName("Chat history and answers");
        conversationScroll = new JBScrollPane(conversation);
        add(conversationScroll, BorderLayout.CENTER);

        questionInput.setLineWrap(true);
        questionInput.setWrapStyleWord(true);
        questionInput.getAccessibleContext().setAccessibleName("Java course question");

        sendButton.addActionListener(event -> sendQuestion());
        questionInput.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "sendQuestion");
        questionInput.getActionMap().put("sendQuestion", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                sendQuestion();
            }
        });

        JPanel inputPanel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
        inputLabel.setLabelFor(questionInput);
        inputPanel.add(inputLabel, BorderLayout.NORTH);
        inputPanel.add(new JBScrollPane(questionInput), BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        codePreview.setEditable(false);
        codePreview.setLineWrap(true);
        codePreview.setWrapStyleWord(true);
        codePreview.getAccessibleContext().setAccessibleName("本次附加的选中代码，只读");
        JPanel codeHeader = new JPanel(new BorderLayout());
        codeHeader.add(codeTitle, BorderLayout.CENTER);
        codeHeader.add(clearCodeButton, BorderLayout.EAST);
        codePanel.add(codeHeader, BorderLayout.NORTH);
        codePanel.add(new JBScrollPane(codePreview), BorderLayout.CENTER);
        codePanel.setVisible(false);
        clearCodeButton.addActionListener(event -> clearCodeContext());
        JPanel composer = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        composer.add(codePanel, BorderLayout.NORTH);
        composer.add(inputPanel, BorderLayout.CENTER);
        add(composer, BorderLayout.SOUTH);
        applyLanguage(ReplyLanguage.EN);
        languageBox.addActionListener(event -> applyLanguage(languageBox.getSelectedIndex() == 0
                ? ReplyLanguage.EN : ReplyLanguage.ZH));
        startIndex(false);
    }

    private void applyLanguage(ReplyLanguage language) {
        boolean changed = language != replyLanguage;
        String previousDefault = replyLanguage.text("解释这段代码。", "Explain this code.");
        replyLanguage = language;
        if (changed && (questionInput.getText().isBlank() || questionInput.getText().equals(previousDefault))) {
            questionInput.setText("");
        }
        rebuildButton.setText(language.text("重建课件索引", "Rebuild index"));
        clearCodeButton.setText(language.text("取消代码上下文", "Remove code"));
        inputLabel.setText(language.text("问题（Ctrl+Enter 发送）：", "Question (Ctrl+Enter to send):"));
        codeTitle.setText(language.text("本次选中代码（点击发送后才会提交）：", "Selected code (sent when you click Send):"));
        codePreview.getAccessibleContext().setAccessibleName(language.text("本次附加的选中代码，只读", "Selected code attached to the next question, read-only"));
        sendButton.setText(busy
                ? language.text("发送中…", "Sending…")
                : language.text("发送", "Send"));
        status.setToolTipText(language.text("课件目录：", "Course folder: ") + knowledge.directory());
        if (!busy) status.setText(language.text("就绪 · DeepSeek / ", "Ready · DeepSeek / ") + DeepSeekClient.MODEL);
        showConversation(false);
        sendButton.revalidate();
        revalidate();
        repaint();
    }

    private String welcomeText() {
        return replyLanguage.text(
                "CourseMate AI\n"
                        + "请输入 Java 课程问题。问题和最多 4 个相关课件片段将交给 DeepSeek。\n"
                        + "优先依据 Lec-0 至 Lec-5；作业要求、展示安排和考试说明不入库。\n"
                        + "来源文件名与 PDF 页码由本地程序核对显示；无相关资料时明确说明。\n"
                        + "课件目录：" + knowledge.directory() + "\n"
                        + "支持 Java 编辑器选区右键 Ask CourseMate AI；确认问题后点击 Send 才会发送代码。\n"
                        + "成功回答后会记住上一问和上一答，下一次追问会带上这一轮。\n\n",
                "CourseMate AI\n"
                        + "Ask a Java course question. The question and at most 4 related lecture excerpts are sent to DeepSeek.\n"
                        + "Answers prefer Lec-0 through Lec-5. Assignment requirements, presentation plans, and exam notes are not indexed.\n"
                        + "File names and PDF page numbers are checked locally. If no relevant material is found, that is stated clearly.\n"
                        + "Course folder: " + knowledge.directory() + "\n"
                        + "In a Java editor, right-click a selection and choose Ask CourseMate AI. The code is sent only after you click Send.\n"
                        + "After a successful answer, the last question and answer are kept and included in the next follow-up.\n\n");
    }

    private void sendQuestion() {
        if (busy || disposed) return;
        String question = questionInput.getText().strip();
        if (question.isEmpty()) {
            questionInput.requestFocusInWindow();
            return;
        }

        appendChat("You:\n" + question + "\n\n");
        String codeForRequest = selectedCode;
        if (!codeForRequest.isEmpty()) {
            appendChat(replyLanguage.text(
                    "[本次附加选中代码：" + codeForRequest.length() + " 字符]\n\n",
                    "[Selected code attached: " + codeForRequest.length() + " characters]\n\n"));
        }
        answering = true;
        setBusy(true);
        scrollToEnd();
        String rememberedQuestion = priorQuestion;
        String rememberedAnswer = priorAnswer;
        ReplyLanguage language = replyLanguage;
        worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws DeepSeekException {
                return answers.answer(question, codeForRequest, rememberedQuestion, rememberedAnswer, language);
            }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    String answer = get();
                    appendChat("CourseMate AI [DeepSeek]:\n"
                            + answer + "\n\n");
                    priorQuestion = question;
                    priorAnswer = answer;
                    questionInput.setText("");
                    clearCodeContext();
                } catch (CancellationException exception) {
                    appendChat(replyLanguage.text("[提示] 请求已取消。\n\n", "[Notice] The request was cancelled.\n\n"));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    appendChat(replyLanguage.text("[提示] 请求已中断，请重试。\n\n", "[Notice] The request was interrupted. Try again.\n\n"));
                } catch (ExecutionException exception) {
                    String safeMessage = exception.getCause() instanceof DeepSeekException error
                            ? error.getMessage()
                            : replyLanguage.text("请求未能完成，请重试。", "The request did not finish. Try again.");
                    appendChat(replyLanguage.text("[错误] ", "[Error] ") + safeMessage + "\n\n");
                } finally {
                    worker = null;
                    answering = false;
                    setBusy(false);
                    scrollToEnd();
                    questionInput.requestFocusInWindow();
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        rebuildButton.setEnabled(!value);
        clearCodeButton.setEnabled(!value);
        sendButton.setText(value ? replyLanguage.text("发送中…", "Sending…") : replyLanguage.text("发送", "Send"));
        questionInput.setEditable(!value);
        status.setText(value
                ? replyLanguage.text("正在等待 DeepSeek 回答…", "Waiting for DeepSeek…")
                : replyLanguage.text("就绪 · DeepSeek / ", "Ready · DeepSeek / ") + DeepSeekClient.MODEL);
    }

    /** Called on the UI thread after activating the tool window. Preparing/cancelling never calls the API. */
    public boolean prepareCodeQuestion(String code) {
        if (disposed || answering || code == null || code.isBlank()) return false;
        selectedCode = code;
        codePreview.setText(code);
        codePreview.setCaretPosition(0);
        codePanel.setVisible(true);
        if (questionInput.getText().isBlank()) {
            questionInput.setText(replyLanguage.text("解释这段代码。", "Explain this code."));
        }
        revalidate();
        repaint();
        questionInput.requestFocusInWindow();
        return true;
    }

    private void clearCodeContext() {
        selectedCode = "";
        codePreview.setText("");
        codePanel.setVisible(false);
        revalidate();
        repaint();
    }

    private void startIndex(boolean force) {
        if (busy || disposed) return;
        setBusy(true);
        status.setText(force
                ? replyLanguage.text("正在重建课件索引…", "Rebuilding the lecture index…")
                : replyLanguage.text("正在加载或建立课件索引…", "Loading the lecture index…"));
        worker = new SwingWorker<CourseKnowledgeBase.Report, Void>() {
            @Override
            protected CourseKnowledgeBase.Report doInBackground() { return knowledge.refresh(force); }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    lastReport = get();
                    indexNote = IndexNote.NONE;
                } catch (CancellationException ignored) {
                    indexNote = IndexNote.CANCELLED;
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    indexNote = IndexNote.INTERRUPTED;
                } catch (ExecutionException ignored) {
                    indexNote = IndexNote.FAILED;
                } finally {
                    worker = null;
                    showConversation(false);
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private String indexSummary(CourseKnowledgeBase.Report report) {
        if (replyLanguage == ReplyLanguage.ZH) return report.summary() + "\n";
        long success = report.documents().stream().filter(file -> !file.chunks().isEmpty()).count();
        StringBuilder text = new StringBuilder("Knowledge base: " + success + " lectures, " + report.chunkCount() + " text chunks.\n");
        for (var file : report.documents()) if (!file.chunks().isEmpty()) {
            text.append(file.name()).append(": text extracted from ").append(file.textPages())
                    .append("/").append(file.pages()).append(" pages\n");
        }
        for (String warning : report.warnings()) text.append("[Knowledge base] ").append(warning).append('\n');
        return text.toString();
    }

    private String indexNoteText() {
        return switch (indexNote) {
            case NONE -> "";
            case CANCELLED -> replyLanguage.text("[知识库提示] 索引已取消。\n", "[Knowledge base] Indexing was cancelled.\n");
            case INTERRUPTED -> replyLanguage.text("[知识库提示] 索引已中断。\n", "[Knowledge base] Indexing was interrupted.\n");
            case FAILED -> replyLanguage.text("[知识库提示] 索引失败，请检查课程目录并点击重建索引。\n",
                    "[Knowledge base] Indexing failed. Check the course folder and rebuild the index.\n");
        };
    }

    private void appendChat(String text) {
        chatLog.append(ChatMarkdown.toHtml(text));
        showConversation(true);
    }

    private void showConversation(boolean scrollToEnd) {
        StringBuilder body = new StringBuilder();
        body.append(ChatMarkdown.toHtml(welcomeText()));
        if (lastReport != null) body.append(ChatMarkdown.toHtml(indexSummary(lastReport)));
        if (indexNote != IndexNote.NONE) body.append(ChatMarkdown.toHtml(indexNoteText()));
        body.append(chatLog);
        paintConversation(body.toString());
        if (scrollToEnd) scrollToEnd();
        else scrollToStart();
    }

    private void paintConversation(String htmlBody) {
        Color foreground = UIManager.getColor("TextArea.foreground");
        Color background = UIManager.getColor("TextArea.background");
        if (foreground == null) foreground = conversation.getForeground() != null ? conversation.getForeground() : Color.BLACK;
        if (background == null) background = conversation.getBackground() != null ? conversation.getBackground() : Color.WHITE;
        conversation.setText("<html><head><style>"
                + "body { color: " + css(foreground) + "; background: " + css(background) + "; margin: 4px; }"
                + "pre, code { font-family: monospace; }"
                + "pre { background: " + css(codeBackground(background)) + "; padding: 6px; white-space: pre-wrap; }"
                + "h1 { font-size: 16px; } h2 { font-size: 15px; } h3, h4, h5, h6 { font-size: 14px; }"
                + "h1, h2, h3, h4, h5, h6 { margin: 8px 0 4px 0; }"
                + "p { margin: 4px 0; }"
                + "</style></head><body>" + htmlBody + "</body></html>");
        conversation.setCaretPosition(conversation.getDocument().getLength());
    }

    private static String css(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color codeBackground(Color background) {
        int delta = background.getRed() + background.getGreen() + background.getBlue() < 384 ? 28 : -18;
        return new Color(clamp(background.getRed() + delta), clamp(background.getGreen() + delta),
                clamp(background.getBlue() + delta));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void scrollToStart() {
        conversation.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> conversationScroll.getVerticalScrollBar().setValue(0));
    }

    private void scrollToEnd() {
        conversation.setCaretPosition(conversation.getDocument().getLength());
        SwingUtilities.invokeLater(() -> {
            var bar = conversationScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private enum IndexNote { NONE, CANCELLED, INTERRUPTED, FAILED }

    @Override
    public void dispose() {
        disposed = true;
        clearCodeContext();
        if (worker != null) worker.cancel(true);
    }
}
