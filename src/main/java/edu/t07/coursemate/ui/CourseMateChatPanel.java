package edu.t07.coursemate.ui;

import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import edu.t07.coursemate.ai.DeepSeekClient;
import edu.t07.coursemate.ai.DeepSeekException;
import edu.t07.coursemate.rag.CourseKnowledgeBase;
import edu.t07.coursemate.rag.RagAnswerService;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Chat UI. All HTTP work runs outside the EDT; completion updates run on the EDT. */
public final class CourseMateChatPanel extends JPanel implements Disposable {
    private final JBTextArea conversation = new JBTextArea();
    private final JBTextArea questionInput = new JBTextArea(3, 20);
    private final JButton sendButton = new JButton("Send");
    private final JBLabel status = new JBLabel("就绪 · DeepSeek / " + DeepSeekClient.MODEL);
    private final DeepSeekClient client = new DeepSeekClient();
    private final JButton rebuildButton = new JButton("重建课件索引");
    private final JBTextArea codePreview = new JBTextArea(4, 20);
    private final JPanel codePanel = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
    private final JButton clearCodeButton = new JButton("取消代码上下文");
    private String selectedCode = "";
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
        header.add(rebuildButton, BorderLayout.EAST);
        rebuildButton.addActionListener(event -> startIndex(true));
        status.setToolTipText("课件目录：" + knowledge.directory());
        add(header, BorderLayout.NORTH);

        conversation.setEditable(false);
        conversation.setLineWrap(true);
        conversation.setWrapStyleWord(true);
        conversation.setText("CourseMate AI\n"
                + "请输入 Java 课程问题。问题和最多 4 个相关课件片段将交给 DeepSeek。\n"
                + "优先依据 Lec-0 至 Lec-5；作业要求、展示安排和考试说明不入库。\n"
                + "来源文件名与 PDF 页码由本地程序核对显示；无相关资料时明确说明。\n"
                + "课件目录：" + knowledge.directory() + "\n"
                + "支持 Java 编辑器选区右键 Ask CourseMate AI；确认问题后点击 Send 才会发送代码。\n"
                + "本版本每次只发送当前问题及显式附加的代码，不携带之前的对话。\n\n");
        conversation.getAccessibleContext().setAccessibleName("Chat history and answers");
        add(new JBScrollPane(conversation), BorderLayout.CENTER);

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
        JBLabel inputLabel = new JBLabel("Question (Ctrl+Enter to send):");
        inputLabel.setLabelFor(questionInput);
        inputPanel.add(inputLabel, BorderLayout.NORTH);
        inputPanel.add(new JBScrollPane(questionInput), BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        codePreview.setEditable(false);
        codePreview.setLineWrap(true);
        codePreview.setWrapStyleWord(true);
        codePreview.getAccessibleContext().setAccessibleName("本次附加的选中代码，只读");
        JPanel codeHeader = new JPanel(new BorderLayout());
        codeHeader.add(new JBLabel("本次选中代码（点击 Send 后发送）："), BorderLayout.CENTER);
        codeHeader.add(clearCodeButton, BorderLayout.EAST);
        codePanel.add(codeHeader, BorderLayout.NORTH);
        codePanel.add(new JBScrollPane(codePreview), BorderLayout.CENTER);
        codePanel.setVisible(false);
        clearCodeButton.addActionListener(event -> clearCodeContext());
        JPanel composer = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        composer.add(codePanel, BorderLayout.NORTH);
        composer.add(inputPanel, BorderLayout.CENTER);
        add(composer, BorderLayout.SOUTH);
        startIndex(false);
    }

    private void sendQuestion() {
        if (busy || disposed) return;
        String question = questionInput.getText().strip();
        if (question.isEmpty()) {
            questionInput.requestFocusInWindow();
            return;
        }

        conversation.append("You:\n" + question + "\n\n");
        String codeForRequest = selectedCode;
        if (!codeForRequest.isEmpty()) conversation.append("[本次附加选中代码：" + codeForRequest.length() + " 字符]\n\n");
        answering = true;
        setBusy(true);
        scrollToEnd();
        worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws DeepSeekException {
                return answers.answer(question, codeForRequest);
            }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    String answer = get();
                    conversation.append("CourseMate AI [DeepSeek]:\n"
                            + answer + "\n\n");
                    questionInput.setText("");
                    clearCodeContext();
                } catch (CancellationException exception) {
                    conversation.append("[提示] 请求已取消。\n\n");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    conversation.append("[提示] 请求已中断，请重试。\n\n");
                } catch (ExecutionException exception) {
                    String safeMessage = exception.getCause() instanceof DeepSeekException error
                            ? error.getMessage() : "请求未能完成，请重试。";
                    conversation.append("[错误] " + safeMessage + "\n\n");
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
        sendButton.setText(value ? "发送中…" : "Send");
        questionInput.setEditable(!value);
        status.setText(value ? "正在等待 DeepSeek 回答…" : "就绪 · DeepSeek / " + DeepSeekClient.MODEL);
    }

    /** Called on the UI thread after activating the tool window. Preparing/cancelling never calls the API. */
    public boolean prepareCodeQuestion(String code) {
        if (disposed || answering || code == null || code.isBlank()) return false;
        selectedCode = code;
        codePreview.setText(code);
        codePreview.setCaretPosition(0);
        codePanel.setVisible(true);
        if (questionInput.getText().isBlank()) questionInput.setText("Explain this code.");
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
        status.setText(force ? "正在重建课件索引…" : "正在加载或建立课件索引…");
        worker = new SwingWorker<CourseKnowledgeBase.Report, Void>() {
            @Override
            protected CourseKnowledgeBase.Report doInBackground() { return knowledge.refresh(force); }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    conversation.append(get().summary() + "\n");
                } catch (CancellationException ignored) {
                    conversation.append("[知识库提示] 索引已取消。\n");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    conversation.append("[知识库提示] 索引已中断。\n");
                } catch (ExecutionException ignored) {
                    conversation.append("[知识库提示] 索引失败，请检查课程目录并点击重建索引。\n");
                } finally {
                    worker = null;
                    setBusy(false);
                    scrollToEnd();
                }
            }
        };
        worker.execute();
    }

    private void scrollToEnd() {
        conversation.setCaretPosition(conversation.getDocument().getLength());
    }

    @Override
    public void dispose() {
        disposed = true;
        clearCodeContext();
        if (worker != null) worker.cancel(true);
    }
}
