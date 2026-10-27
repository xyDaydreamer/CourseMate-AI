package edu.t07.coursemate.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.util.ui.JBUI;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.chat.*;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;

/** Each turn owns its immutable evidence and translation cache; display chrome is separate from answer prose. */
final class TurnCard extends ChatColumn {
    final ChatSession.Request request;
    AnswerResult answer;
    final Map<ReplyLanguage,List<String>> translations=new EnumMap<>(ReplyLanguage.class);
    private final JLabel user=new JLabel(),assistant=new JLabel("CourseMate AI"),sourcesTitle=new JLabel();
    private final ChatColumn body=new ChatColumn(7),sources=new ChatColumn(4);
    private final JButton copy=new JButton(),translate=new JButton();
    private final List<Disclosure> codeDisclosures=new ArrayList<>();
    private final BiConsumer<TurnCard,ReplyLanguage> translateAction;
    private final java.util.function.Consumer<AnswerResult.Source> openSource;
    private ReplyLanguage language,displayedLanguage;
    private Throwable error;
    private String state="WAITING";
    private boolean translating;

    TurnCard(ChatSession.Request request,ReplyLanguage language,BiConsumer<TurnCard,ReplyLanguage> translator,java.util.function.Consumer<AnswerResult.Source> opener){
        super(9);this.request=request;this.language=language;displayedLanguage=request.language();translateAction=translator;openSource=opener;
        setBorder(JBUI.Borders.compound(new RoundedBorder(),JBUI.Borders.empty(12)));
        role(user);role(assistant);section(sourcesTitle);
        add(user);add(new RichText(request.question(),false));
        if(!request.code().empty()){var code=new Disclosure("CODE",request.code().text());codeDisclosures.add(code);add(code);}
        add(assistant);add(body);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));actions.setOpaque(false);actions.add(copy);actions.add(translate);add(actions);
        add(sourcesTitle);add(sources);
        actionStyle(copy);actionStyle(translate);
        copy.addActionListener(e->CopyPasteManager.getInstance().setContents(new StringSelection(displayedProse())));
        translate.addActionListener(e->translateAction.accept(this,this.language));
        setLanguage(language);
    }
    static JPanel column(){return new ChatColumn();}
    private static void role(JLabel label){Font font=UIManager.getFont("Label.font");if(font!=null)label.setFont(font.deriveFont(Font.BOLD));}
    private static void section(JLabel label){Font font=UIManager.getFont("Label.font");if(font!=null)label.setFont(font.deriveFont(Font.BOLD,Math.max(11f,font.getSize2D()-1)));Color muted=UIManager.getColor("Label.disabledForeground");if(muted!=null)label.setForeground(muted);}
    private static void actionStyle(AbstractButton button){
        button.setContentAreaFilled(false);button.setBorderPainted(false);button.setMargin(JBUI.insets(3,0));
        button.setRolloverEnabled(true);
        button.getModel().addChangeListener(e->updateActionColor(button));
        button.addFocusListener(new FocusAdapter(){@Override public void focusGained(FocusEvent e){updateActionColor(button);}@Override public void focusLost(FocusEvent e){updateActionColor(button);}});
        button.addPropertyChangeListener("UI",e->updateActionColor(button));
        updateActionColor(button);
        Font font=UIManager.getFont("Label.font");if(font!=null)button.setFont(font.deriveFont(Math.max(11f,font.getSize2D()-1)));
    }
    private static void updateActionColor(AbstractButton button){
        Color background=UIManager.getColor("Panel.background");
        boolean dark=background!=null&&background.getRed()+background.getGreen()+background.getBlue()<384;
        Color normal=UIManager.getColor("Link.activeForeground");
        if(normal==null)normal=new Color(dark?0x6BA5FF:0x235DC2);
        var model=button.getModel();Color color=normal;
        if(!button.isEnabled()){
            color=UIManager.getColor("Label.disabledForeground");if(color==null)color=Color.GRAY;
        }else if(model.isArmed()&&model.isPressed()||model.isSelected()){
            color=UIManager.getColor("Link.pressedForeground");if(color==null||color.equals(normal))color=mix(normal,dark?Color.WHITE:Color.BLACK,0.42);
        }else if(model.isRollover()||button.hasFocus()){
            color=UIManager.getColor("Link.hoverForeground");if(color==null||color.equals(normal))color=mix(normal,dark?Color.WHITE:Color.BLACK,0.23);
        }
        button.setForeground(color);button.setCursor(Cursor.getPredefinedCursor(button.isEnabled()?Cursor.HAND_CURSOR:Cursor.DEFAULT_CURSOR));
    }
    private static Color mix(Color from,Color to,double amount){return new Color((int)(from.getRed()*(1-amount)+to.getRed()*amount),(int)(from.getGreen()*(1-amount)+to.getGreen()*amount),(int)(from.getBlue()*(1-amount)+to.getBlue()*amount));}
    private static RichText note(String text){return new RichText(text,true);}
    private static JTextArea literal(String text){JTextArea a=new JTextArea(text);a.setEditable(false);a.setLineWrap(true);a.setWrapStyleWord(true);Font base=UIManager.getFont("Label.font");a.setFont(new Font(Font.MONOSPACED,Font.PLAIN,base==null?13:base.getSize()));return a;}
    void complete(AnswerResult result){answer=result;error=null;state="COMPLETE";List<String> original=new ArrayList<>(answer.claims().stream().map(AnswerResult.Claim::text).toList());original.add(answer.generalAnswer());translations.put(request.language(),List.copyOf(original));displayedLanguage=request.language();setLanguage(language);}
    void fail(Throwable failure){error=failure;state="FAILED";renderAnswer();renderSources();}
    void cancel(){state="CANCELLED";error=null;translating=false;renderAnswer();renderSources();}
    void translationReady(ReplyLanguage target,List<String> values){translations.put(target,List.copyOf(values));displayedLanguage=target;translating=false;setLanguage(language);}
    void translationFailed(Throwable failure){translating=false;error=failure;setLanguage(language);}
    void showCached(ReplyLanguage target){if(translations.containsKey(target)){displayedLanguage=target;renderAnswer();}}
    void translating(boolean value){translating=value;setLanguage(language);}
    private String displayedProse(){return answer==null?"":String.join("\n\n",translations.getOrDefault(displayedLanguage,List.of(answer.prose())));}
    void setLanguage(ReplyLanguage l){
        language=l;user.setText(l.text("你","You"));sourcesTitle.setText(l.text("课程来源","Course sources"));copy.setText(l.text("复制回答","Copy answer"));
        // The language selector automatically displays an available cached version. No redundant 'show language' button.
        if(translations.containsKey(l))displayedLanguage=l;
        translate.setText(translating?l.text("翻译中…","Translating…"):l.text("翻译本条回答","Translate this answer"));
        translate.setVisible(answer!=null&&!translations.containsKey(l));
        copy.setEnabled(answer!=null&&!answer.prose().isBlank());translate.setEnabled(answer!=null&&answer.completion()==AnswerResult.Completion.COMPLETE&&!translating);
        codeDisclosures.forEach(Disclosure::localize);renderAnswer();renderSources();
    }
    private void renderAnswer(){
        body.removeAll();
        if(answer==null){body.add(note(switch(state){case "CANCELLED"->language.text("请求已停止。","Request stopped.");case "FAILED"->UiText.error(error,language);default->language.text("正在检索并生成回答…","Retrieving evidence and generating an answer…");}));}
        else{
            if(answer.completion()==AnswerResult.Completion.TRUNCATED)body.add(note(language.text("回答达到长度限制，未完整生成；没有自动重试，也未展示未经校验的课程正文。","The answer reached the output limit. It was not retried; unchecked structured content was hidden.")));
            List<String> values=translations.getOrDefault(displayedLanguage,List.of());
            if(!answer.claims().isEmpty()){
                int claimCount=answer.claims().size();
                String course=values.size()>=claimCount
                        ? String.join("\n\n", values.subList(0, claimCount))
                        : answer.claims().stream().map(AnswerResult.Claim::text).reduce((a,b)->a+"\n\n"+b).orElse("");
                body.add(new RichText(course,false));
            }
            String general=values.isEmpty()?answer.generalAnswer():values.getLast();
            if(answer.claims().isEmpty()){
                body.add(new RichText(language.text(
                        "回答基于通用知识，未引用具体课程资料。",
                        "Response is based on general knowledge; no specific course material is referenced."), false));
            }
            if(!general.isBlank()){
                body.add(note(answer.claims().isEmpty()?language.text("通用回答 · 本轮未引用课程资料","General answer · no course citation"):language.text("补充说明 · 不由课程资料证明","Additional explanation · not established by course evidence")));
                body.add(new RichText(general,false));
            }
            for(String warning:answer.warnings())body.add(note(UiText.notice(warning,language)));
        }
        if(answer!=null&&error!=null)body.add(note(UiText.error(error,language)));
        revalidate();repaint();
    }
    private void renderSources(){
        sources.removeAll();
        if(answer==null){sources.add(note(state.equals("FAILED")?language.text("回答未完成校验，本轮没有可展示的已使用引用。","The answer did not complete validation; no used citation is available."):language.text("本轮尚无已使用引用。","No used citation yet.")));return;}
        if(answer.sources().isEmpty()){
            String reason=answer.basis()==AnswerResult.Basis.CANDIDATES_UNUSED?language.text("已检索到候选，但回答未采用；没有把候选列为引用。","Candidates were retrieved but not used; they are not listed as citations."):UiText.retrieval(answer.retrieval().status(),language);
            sources.add(note(reason));
        }else{
            sources.add(note(language.text("请核对原文是否支持结论；来源校验不等于事实验证。","Check whether the excerpts support the claims; source validation is not fact-checking.")));
            for(var source:answer.sources()){
                JButton open=new JButton("["+source.id()+"] "+source.chunk().fileName()+language.text(" · 第 "," · page ")+source.chunk().page()+language.text(" 页",""));
                actionStyle(open);
                open.setHorizontalAlignment(SwingConstants.LEFT);open.setBorderPainted(false);open.setContentAreaFilled(false);open.setMargin(JBUI.insets(2,0));
                open.setToolTipText(source.chunk().chunkId()+" · "+source.chunk().startOffset()+"–"+source.chunk().endOffset());open.addActionListener(e->openSource.accept(source));sources.add(open);
                sources.add(new Disclosure("EXCERPT",source.chunk().text()));
            }
        }
        revalidate();repaint();
    }
    private final class Disclosure extends JPanel{
        final JToggleButton toggle=new JToggleButton();final JScrollPane text;final String kind;
        Disclosure(String kind,String content){super(new BorderLayout(0,4));this.kind=kind;setOpaque(false);text=new JScrollPane(literal(content));text.setPreferredSize(new Dimension(300,150));text.setVisible(false);toggle.setHorizontalAlignment(SwingConstants.LEFT);actionStyle(toggle);add(toggle,BorderLayout.NORTH);add(text,BorderLayout.CENTER);toggle.addActionListener(e->{text.setVisible(toggle.isSelected());TurnCard.this.revalidate();});localize();}
        void localize(){toggle.setText(kind.equals("CODE")?request.historicalCode()?language.text("查看沿用的代码快照","View reused code snapshot"):language.text("查看选中代码","View selected code"):language.text("展开原文","Show excerpt"));}
    }
    private static final class RoundedBorder extends AbstractBorder{
        @Override public Insets getBorderInsets(Component c){return new Insets(1,1,1,1);}
        @Override public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){Graphics2D copy=(Graphics2D)g.create();copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color color=UIManager.getColor("Separator.foreground");copy.setColor(color==null?Color.GRAY:color);copy.drawRoundRect(x,y,w-1,h-1,12,12);copy.dispose();}
    }
}
