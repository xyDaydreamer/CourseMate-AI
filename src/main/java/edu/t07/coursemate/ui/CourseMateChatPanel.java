package edu.t07.coursemate.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import edu.t07.coursemate.rag.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Swing chat with immutable per-turn snapshots and a generation fence around all asynchronous callbacks. */
public final class CourseMateChatPanel extends JPanel implements Disposable {
    private final CourseKnowledgeBase knowledge;
    private final RagAnswerService answers;
    private final AnswerTranslation translation;
    private ChatSession session = new ChatSession();
    private final JPanel messages = new MessageList();
    private final JScrollPane scroll = new JBScrollPane(messages);
    private List<TurnCard> cards = new ArrayList<>();
    private final JBTextArea input = new JBTextArea(3,20), codePreview = new JBTextArea(4,20);
    private final JPanel codePanel = new JPanel(new BorderLayout());
    private final JButton send = new JButton(), stop = new JButton(), fresh = new JButton(), rebuild = new JButton(), removeCode = new JButton();
    private final JComboBox<String> languages = new JComboBox<>(new String[]{"English","中文"});
    private final JCheckBox inheritCode = new JCheckBox();
    private final JTextArea reuseNotice = new JTextArea();
    private final JLabel status = new JLabel(), keyStatus = new JLabel(), folder = new JLabel(), indexStatus = new JLabel(), inputLabel = new JLabel(), contextLabel = new JLabel();
    private final JTextArea warnings = new JTextArea();
    private final JComboBox<ConversationView> historyChoice = new JComboBox<>();
    private final JLabel historyLabel = new JLabel();
    private final List<ConversationView> conversations = new ArrayList<>();
    private ConversationView currentConversation;
    private int conversationNumber;
    private boolean switchingHistory;
    private ReplyLanguage language = ReplyLanguage.EN;
    private ChatSession.Code selectedCode = ChatSession.Code.of("");
    private SwingWorker<?,?> worker;
    private TurnCard activeCard;
    private CourseKnowledgeBase.Report report;
    private boolean disposed, busy, omittedCards;
    private String activity = "READY";
    private long operation;

    public CourseMateChatPanel(String basePath) {
        this(new CourseKnowledgeBase(CourseKnowledgeBase.locate(basePath)), new DeepSeekClient());
    }
    private CourseMateChatPanel(CourseKnowledgeBase knowledge, DeepSeekClient client) {
        this(knowledge, RagAnswerService.structured(knowledge, client::askResponse), new AnswerTranslation(client::translateResponse));
    }
    // Injection seam for UI state tests: no credentials or network required.
    CourseMateChatPanel(CourseKnowledgeBase knowledge, RagAnswerService answers, AnswerTranslation translation) {
        super(new BorderLayout(0,JBUI.scale(8))); this.knowledge=knowledge; this.answers=answers; this.translation=translation;
        setBorder(JBUI.Borders.empty(8));
        JPanel header=TurnCard.column();
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        actions.add(languages); actions.add(fresh); actions.add(rebuild); actions.add(stop);
        header.add(actions);
        JPanel historyRow=new JPanel(new BorderLayout(6,0));historyRow.setOpaque(false);historyRow.add(historyLabel,BorderLayout.WEST);historyRow.add(historyChoice,BorderLayout.CENTER);
        historyChoice.getAccessibleContext().setAccessibleName("Conversation history");
        currentConversation=new ConversationView(++conversationNumber,session,cards);conversations.add(currentConversation);historyChoice.addItem(currentConversation);
        historyChoice.addActionListener(e->{if(!switchingHistory&&historyChoice.getSelectedItem() instanceof ConversationView view&&view!=currentConversation)switchConversation(view);});
        header.add(historyRow);header.add(status); header.add(keyStatus); header.add(folder); header.add(indexStatus);
        warnings.setEditable(false); warnings.setLineWrap(true); warnings.setWrapStyleWord(true); warnings.setOpaque(false); header.add(warnings);
        add(header,BorderLayout.NORTH); add(scroll,BorderLayout.CENTER);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.getAccessibleContext().setAccessibleName("Question");
        codePreview.setEditable(false); codePreview.setLineWrap(true); codePreview.setWrapStyleWord(true);
        JPanel codeActions=new JPanel(new BorderLayout()); codeActions.add(contextLabel,BorderLayout.CENTER); codeActions.add(removeCode,BorderLayout.EAST);
        codePanel.add(codeActions,BorderLayout.NORTH); codePanel.add(new JBScrollPane(codePreview),BorderLayout.CENTER); codePanel.setVisible(false);
        JPanel composer=TurnCard.column(); composer.add(codePanel); composer.add(inheritCode); composer.add(reuseNotice); composer.add(inputLabel);
        reuseNotice.setEditable(false);reuseNotice.setLineWrap(true);reuseNotice.setWrapStyleWord(true);reuseNotice.setOpaque(false);
        Font hintFont=UIManager.getFont("Label.font");if(hintFont!=null)reuseNotice.setFont(hintFont.deriveFont(Math.max(11f,hintFont.getSize2D()-1)));
        Color hintColor=UIManager.getColor("Label.disabledForeground");if(hintColor!=null)reuseNotice.setForeground(hintColor);
        JPanel editing=new JPanel(new BorderLayout(6,0)); editing.add(new JBScrollPane(input),BorderLayout.CENTER); editing.add(send,BorderLayout.EAST); composer.add(editing);
        add(composer,BorderLayout.SOUTH);
        inputLabel.setLabelFor(input); inheritCode.setSelected(true);
        send.addActionListener(e->sendQuestion()); stop.addActionListener(e->stopRequest()); fresh.addActionListener(e->newConversation()); rebuild.addActionListener(e->startIndex(true));
        removeCode.addActionListener(e->{selectedCode=ChatSession.Code.of(""); session.forgetCode(); inheritCode.setSelected(false); updateCodePreview();});
        inheritCode.addActionListener(e->updateCodePreview());
        languages.addActionListener(e->{language=languages.getSelectedIndex()==0?ReplyLanguage.EN:ReplyLanguage.ZH; localize();});
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.CTRL_DOWN_MASK),"send");
        input.getActionMap().put("send",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){sendQuestion();}});
        input.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){updateCodePreview();} public void removeUpdate(DocumentEvent e){updateCodePreview();} public void changedUpdate(DocumentEvent e){updateCodePreview();}});
        align(header); align(composer); localize(); startIndex(false);
    }
    private static void align(Container parent) {
        for(Component child:parent.getComponents()){
            if(child instanceof JComponent component)component.setAlignmentX(LEFT_ALIGNMENT);
            if(child instanceof Container container)align(container);
        }
    }
    private void localize() { localize(true); }
    private void localize(boolean updateCards) {
        historyLabel.setText(language.text("会话记录","Chat history"));historyChoice.setToolTipText(language.text("当前窗口内保留最近 10 个会话；关闭插件窗口后不保留。","The latest 10 chats are retained in this panel only; they are not saved after it closes."));historyChoice.repaint();
        fresh.setText(language.text("新对话","New conversation")); rebuild.setText(language.text("重建索引","Rebuild index")); stop.setText(language.text("停止","Stop"));
        send.setText(language.text("发送","Send")); removeCode.setText(language.text("移除代码","Remove code"));
        inheritCode.setText(language.text("追问时允许沿用上轮选中代码","Allow reusing the last selected code for follow-ups"));
        inputLabel.setText(language.text("问题（Ctrl+Enter 发送）","Question (Ctrl+Enter to send)"));
        boolean key=System.getenv("DEEPSEEK_API_KEY")!=null&&!System.getenv("DEEPSEEK_API_KEY").isBlank();
        keyStatus.setText(key?language.text("API Key：已配置，尚未验证有效性","API key: configured; validity not checked"):language.text("API Key：未配置，请通过启动器配置后重启","API key: missing; configure through the launcher and restart"));
        folder.setText(language.text("课件目录：","Course folder: ")+knowledge.directory().getFileName()); folder.setToolTipText(knowledge.directory().toString());
        indexStatus.setText(report==null?language.text("索引：尚未加载","Index: not loaded"):
                language.text("索引：","Index: ")+report.documents().stream().filter(f->!f.chunks().isEmpty()).count()+language.text(" 份讲义 / "," lectures / ")+report.chunkCount()+language.text(" 个片段"," chunks"));
        List<String> notices=new ArrayList<>();
        if(report!=null) report.warnings().forEach(w->notices.add(UiText.notice(w,language)));
        if(omittedCards) notices.add(language.text("仅显示最近 30 轮；更早的界面记录已移除。","Only the latest 30 turns are displayed; older cards were removed."));
        warnings.setText(String.join("\n",notices)); warnings.setVisible(!notices.isEmpty());
        status.setText(switch(activity){
            case "INDEXING"->language.text("正在建立课程索引…","Indexing course material…");
            case "ASKING"->language.text("正在检索并等待模型…","Retrieving evidence and waiting for the model…");
            case "TRANSLATING"->language.text("正在翻译本条回答…","Translating this answer…");
            case "CANCELLED"->language.text("请求已停止","Request stopped");
            case "INDEX_FAILED"->language.text("索引加载失败，请检查课件路径","Indexing failed; check the course folder");
            default->language.text("就绪 · ","Ready · ")+DeepSeekClient.MODEL;
        });
        if(updateCards) for(TurnCard card:cards) card.setLanguage(language);
        updateCodePreview(); revalidate(); repaint();
    }
    private void updateCodePreview() {
        boolean inherited=selectedCode.empty()&&inheritCode.isSelected()&&session.willUseHistoricalCode(input.getText());
        ChatSession.Code code=selectedCode.empty()?(inherited?session.rememberedCode():ChatSession.Code.of("")):selectedCode;
        codePanel.setVisible(!code.empty());
        contextLabel.setText(inherited?language.text("本轮将使用上轮明确选中的代码快照","This turn will reuse the previously selected code snapshot"):
                language.text("本轮选中的代码快照","Selected code snapshot for this turn"));
        if(!codePreview.getText().equals(code.text())){codePreview.setText(code.text()); codePreview.setCaretPosition(0);}
        reuseNotice.setText(language.text("当前按新问题处理，不沿用旧代码；承接上文时会显示代码预览。","New question: previous code is not included; follow-ups show a code preview."));
        reuseNotice.setVisible(selectedCode.empty()&&inheritCode.isSelected()&&!session.rememberedCode().empty()&&!input.getText().isBlank()&&!inherited);
        revalidate();
    }
    public boolean prepareCodeQuestion(String code) { return prepareCodeQuestion(ChatSession.Code.of(code)); }
    public boolean prepareCodeQuestion(ChatSession.Code code) {
        if(disposed||busy||code==null||code.empty())return false;
        selectedCode=code;
        if(input.getText().isBlank())input.setText(language.text("解释这段代码。","Explain this code."));
        updateCodePreview(); input.requestFocusInWindow(); return true;
    }
    private void setBusy(boolean value) {
        busy=value; send.setEnabled(!value); rebuild.setEnabled(!value); stop.setEnabled(value); input.setEditable(!value); removeCode.setEnabled(!value); inheritCode.setEnabled(!value);
        localize(false);
    }
    private void sendQuestion() {
        if(busy||disposed)return;
        ChatSession.Request request;
        try{request=session.prepare(input.getText(),selectedCode,inheritCode.isSelected(),language);}
        catch(DeepSeekException e){JOptionPane.showMessageDialog(this,UiText.error(e,language));return;}
        TurnCard card=new TurnCard(request,language,this::translateCard,this::openSource); activeCard=card;
        if(currentConversation.title.isBlank()){currentConversation.title=request.question().replace('\n',' ');historyChoice.repaint();}
        cards.add(card); messages.add(card); messages.add(Box.createVerticalStrut(8));
        while(cards.size()>30){TurnCard old=cards.removeFirst();messages.remove(old);if(messages.getComponentCount()>0)messages.remove(0);omittedCards=true;}
        activity="ASKING"; setBusy(true); long generation=++operation;
        SwingWorker<AnswerResult,Void> next=new SwingWorker<>(){
            @Override protected AnswerResult doInBackground()throws DeepSeekException{return answers.ask(request);}
            @Override protected void done(){
                if(disposed||operation!=generation||!session.current(request))return;
                try{
                    AnswerResult answer=get(); card.complete(answer);
                    if(answer.completion()==AnswerResult.Completion.COMPLETE){session.commit(request,answer.prose());input.setText("");selectedCode=ChatSession.Code.of("");}
                    else session.cancel();
                }catch(CancellationException e){card.cancel();session.cancel();}
                catch(InterruptedException e){Thread.currentThread().interrupt();card.cancel();session.cancel();}
                catch(ExecutionException e){card.fail(e.getCause());session.cancel();}
                finally{worker=null;activeCard=null;activity="READY";setBusy(false);scrollEnd();}
            }
        }; worker=next;next.execute();scrollEnd();
    }
    private void stopRequest(){
        ++operation;session.cancel(); SwingWorker<?,?> current=worker;worker=null;
        if(current!=null)current.cancel(true); if(activeCard!=null){activeCard.cancel();activeCard.translating(false);} activeCard=null;
        activity="CANCELLED";setBusy(false);
    }
    private final class ConversationView {
        final int number;final ChatSession session;final List<TurnCard> cards;String title="",draft="";ChatSession.Code code=ChatSession.Code.of("");boolean allowCode=true,omitted;
        ConversationView(int number,ChatSession session,List<TurnCard> cards){this.number=number;this.session=session;this.cards=cards;}
        @Override public String toString(){String text=title.isBlank()?language.text("新对话","New conversation"):title;return language.text("对话 ","Chat ")+number+" · "+(text.length()>32?text.substring(0,32)+"…":text);}
    }
    private void saveCurrentConversation(){if(currentConversation!=null){currentConversation.draft=input.getText();currentConversation.code=selectedCode;currentConversation.allowCode=inheritCode.isSelected();currentConversation.omitted=omittedCards;}}
    private void switchConversation(ConversationView view){
        stopRequest();saveCurrentConversation();currentConversation=view;session=view.session;cards=view.cards;
        selectedCode=view.code;omittedCards=view.omitted;inheritCode.setSelected(view.allowCode);input.setText(view.draft);
        messages.removeAll();for(TurnCard card:cards){messages.add(card);messages.add(Box.createVerticalStrut(8));}
        activity="READY";switchingHistory=true;historyChoice.setSelectedItem(view);switchingHistory=false;localize();scrollEnd();
    }
    private void newConversation(){
        ConversationView view=new ConversationView(++conversationNumber,new ChatSession(),new ArrayList<>());
        switchingHistory=true;conversations.add(view);historyChoice.addItem(view);switchingHistory=false;switchConversation(view);
        while(conversations.size()>10){ConversationView old=conversations.removeFirst();switchingHistory=true;historyChoice.removeItem(old);switchingHistory=false;}
    }
    private void startIndex(boolean force){
        if(busy||disposed)return;activity="INDEXING";setBusy(true);long generation=++operation;
        SwingWorker<CourseKnowledgeBase.Report,Void> next=new SwingWorker<>(){
            @Override protected CourseKnowledgeBase.Report doInBackground(){return knowledge.refresh(force);}
            @Override protected void done(){
                if(disposed||operation!=generation)return;
                try{report=get();activity="READY";}catch(CancellationException e){activity="CANCELLED";}
                catch(Exception e){activity="INDEX_FAILED";}
                finally{worker=null;setBusy(false);}
            }
        };worker=next;next.execute();
    }
    private void translateCard(TurnCard card,ReplyLanguage target){
        if(busy||disposed||card.answer==null)return;
        if(card.translations.containsKey(target)){card.showCached(target);return;}
        activity="TRANSLATING";setBusy(true);card.translating(true);activeCard=card;long generation=++operation;
        SwingWorker<List<String>,Void> next=new SwingWorker<>(){
            @Override protected List<String> doInBackground()throws DeepSeekException{return translation.translate(card.answer,target);}
            @Override protected void done(){
                if(disposed||operation!=generation)return;
                try{card.translationReady(target,get());}
                catch(CancellationException e){card.translating(false);}
                catch(InterruptedException e){Thread.currentThread().interrupt();card.translating(false);}
                catch(ExecutionException e){card.translationFailed(e.getCause());}
                finally{worker=null;activeCard=null;activity="READY";setBusy(false);}
            }
        };worker=next;next.execute();
    }
    private void openSource(AnswerResult.Source source){
        Path file=knowledge.directory().resolve(source.chunk().fileName()).normalize();
        if(!file.startsWith(knowledge.directory())||!Files.isRegularFile(file)){JOptionPane.showMessageDialog(this,language.text("来源文件不存在。","The source file is unavailable."));return;}
        new SwingWorker<Boolean,Void>(){
            @Override protected Boolean doInBackground()throws Exception{
                if(source.chunk().documentHash().isBlank())return true;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).equals(source.chunk().documentHash());
            }
            @Override protected void done(){
                if(disposed)return;
                try{if(get())BrowserUtil.browse(file.toUri().toASCIIString()+"#page="+source.chunk().page());
                else JOptionPane.showMessageDialog(CourseMateChatPanel.this,language.text("来源 PDF 已改变。保留的原文仍是当时快照；请重新提问以使用新版本。","The PDF has changed. The saved excerpt is the original snapshot; ask again to use the new version."));}
                catch(Exception e){JOptionPane.showMessageDialog(CourseMateChatPanel.this,language.text("无法打开来源 PDF。","Cannot open the source PDF."));}
            }
        }.execute();
    }
    private static final class MessageList extends ChatColumn implements Scrollable {
        MessageList(){super(8);}
        @Override public Dimension getPreferredScrollableViewportSize(){return new Dimension(480,600);}
        @Override public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 20;}
        @Override public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(20,r.height-40);}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private void scrollEnd(){messages.revalidate();SwingUtilities.invokeLater(()->scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));}
    @Override public void dispose(){disposed=true;++operation;session.clear();if(worker!=null)worker.cancel(true);selectedCode=ChatSession.Code.of("");for(ConversationView view:conversations){view.session.clear();view.cards.clear();}conversations.clear();switchingHistory=true;historyChoice.removeAllItems();switchingHistory=false;cards.clear();messages.removeAll();}
}
