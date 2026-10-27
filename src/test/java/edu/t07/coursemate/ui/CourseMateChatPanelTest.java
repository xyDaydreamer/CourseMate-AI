package edu.t07.coursemate.ui;

import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import edu.t07.coursemate.rag.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CourseMateChatPanelTest {
    @TempDir Path temp;
    private CourseMateChatPanel create(RagAnswerService.ModelGenerator generator, AtomicInteger translations) throws Exception {
        var kb=new CourseKnowledgeBase(temp,temp.resolve("index.json"),new PdfCourseReader());
        AtomicReference<CourseMateChatPanel> result=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->result.set(new CourseMateChatPanel(kb,RagAnswerService.structured(kb,generator),new AnswerTranslation(json->{
            translations.incrementAndGet();var root=com.google.gson.JsonParser.parseString(json).getAsJsonObject();var out=new com.google.gson.JsonObject();out.add("segments",root.get("segments"));return ModelResponse.complete(out.toString());
        }))));
        await(()->button(result.get(),"Send").isEnabled());return result.get();
    }
    @Test void cardsLanguageTranslationCacheAndSourceRegionRemainIndependent() throws Exception {
        AtomicInteger calls=new AtomicInteger(), translations=new AtomicInteger();
        var panel=create((q,s)->{calls.incrementAndGet();return ModelResponse.complete("Existing answer in notes.pdf.\n```java\nint i=1;\n```");},translations);
        try{
            submit(panel,"First question");await(()->find(panel,TurnCard.class).size()==1&&find(panel,TurnCard.class).getFirst().answer!=null);
            submit(panel,"Second question");await(()->find(panel,TurnCard.class).size()==2&&find(panel,TurnCard.class).getLast().answer!=null);
            SwingUtilities.invokeAndWait(()->find(panel,JComboBox.class).getFirst().setSelectedIndex(1));
            assertEquals(2,calls.get());
            SwingUtilities.invokeAndWait(()->button(find(panel,TurnCard.class).getFirst(),"翻译本条回答").doClick());
            await(()->translations.get()==1&&button(panel,"发送").isEnabled());
            SwingUtilities.invokeAndWait(()->{var box=find(panel,JComboBox.class).getFirst();box.setSelectedIndex(0);box.setSelectedIndex(1);assertTrue(find(panel,JButton.class).stream().noneMatch(b->b.isVisible()&&b.getText().contains("显示本语言")));});
            assertEquals(1,translations.get());assertEquals(2,calls.get());
            assertEquals(2,find(panel,JLabel.class).stream().filter(l->l.getText().equals("课程来源")).count());
            SwingUtilities.invokeAndWait(()->{
                panel.setSize(640,900);layout(panel);BufferedImage img=new BufferedImage(640,900,BufferedImage.TYPE_INT_RGB);
                var graphics=img.createGraphics();panel.printAll(graphics);graphics.dispose();
                try{Path output=Path.of("build/reports/ui/cards-zh.png");Files.createDirectories(output.getParent());ImageIO.write(img,"png",output.toFile());}catch(Exception e){throw new RuntimeException(e);}
            });
        }finally{SwingUtilities.invokeAndWait(panel::dispose);}
    }
    @Test void stoppingAndStartingNewConversationRejectsLateResult() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        var panel=create((q,s)->{
            if(calls.incrementAndGet()==1){entered.countDown();boolean waiting=true;while(waiting){try{release.await();waiting=false;}catch(InterruptedException ignored){}}return ModelResponse.complete("LATE_OLD_ANSWER");}
            return ModelResponse.complete("NEW_ANSWER");
        },new AtomicInteger());
        try{
            submit(panel,"Old question");assertTrue(entered.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{button(panel,"Stop").doClick();button(panel,"New conversation").doClick();});
            submit(panel,"New question");await(()->find(panel,TurnCard.class).size()==1&&find(panel,TurnCard.class).getFirst().answer!=null);
            release.countDown();await(()->button(panel,"Send").isEnabled());
            assertEquals("NEW_ANSWER",find(panel,TurnCard.class).getFirst().answer.prose());assertEquals(1,find(panel,TurnCard.class).size());
        }finally{release.countDown();SwingUtilities.invokeAndWait(panel::dispose);}
    }
    @Test void correctionFollowUpPreviewMatchesTheActualRequestAndReuseCanBeDisabled() throws Exception {
        List<com.google.gson.JsonObject> sent=new CopyOnWriteArrayList<>();
        var panel=create((q,s)->{sent.add(com.google.gson.JsonParser.parseString(q).getAsJsonObject());return ModelResponse.complete("A contextual answer.");},new AtomicInteger());
        String code="Map<String, Integer> counts = new HashMap";
        try{
            SwingUtilities.invokeAndWait(()->panel.prepareCodeQuestion(code));
            submit(panel,"解释这段代码。");await(()->button(panel,"Send").isEnabled()&&sent.size()==1);
            SwingUtilities.invokeAndWait(()->{
                question(panel).setText("修改后应该是什么样？");
                assertTrue(find(panel,JLabel.class).stream().anyMatch(l->l.isVisible()&&l.getText().contains("reuse the previously selected")));
                assertTrue(find(panel,JTextArea.class).stream().anyMatch(a->a.isVisible()&&a.getText().equals(code)));
            });
            submit(panel,"修改后应该是什么样？");await(()->button(panel,"Send").isEnabled()&&sent.size()==2);
            assertEquals(code,sent.get(1).get("selected_code").getAsString());
            assertTrue(sent.get(1).get("historical_code").getAsBoolean());
            assertEquals("解释这段代码。",sent.get(1).get("resolved_question").getAsString());
            assertFalse(sent.get(1).getAsJsonArray("history_for_reference_only").isEmpty());
            SwingUtilities.invokeAndWait(()->{
                question(panel).setText("Explain inheritance.");
                assertTrue(find(panel,JTextArea.class).stream().anyMatch(a->a.isVisible()&&a.getText().startsWith("New question:")));
            });
            SwingUtilities.invokeAndWait(()->find(panel,JCheckBox.class).getFirst().doClick());
            submit(panel,"那这个怎么改？");await(()->button(panel,"Send").isEnabled()&&sent.size()==3);
            assertFalse(sent.get(2).has("selected_code"));
        }finally{SwingUtilities.invokeAndWait(panel::dispose);}
    }
    private static JTextArea question(CourseMateChatPanel panel){return find(panel,JTextArea.class).stream().filter(a->"Question".equals(a.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
    @Test void formatFailureKeepsInputAndDoesNotBlockTheFollowingQuestion() throws Exception {
        AtomicInteger calls=new AtomicInteger();var panel=create((q,s)->{if(calls.incrementAndGet()==1)throw new DeepSeekException(DeepSeekException.Kind.FORMAT,"Invalid answer JSON",0,null);return ModelResponse.complete("Recovered answer.");},new AtomicInteger());
        try {
            submit(panel,"First question");await(()->button(panel,"Send").isEnabled()&&find(panel,TurnCard.class).size()==1&&find(panel,TurnCard.class).getFirst().answer!=null);
            assertEquals("Recovered answer.",find(panel,TurnCard.class).getFirst().answer.prose());
            assertEquals("",find(panel,JTextArea.class).stream().filter(a->"Question".equals(a.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().getText());
            assertEquals(2,calls.get());
        }finally{SwingUtilities.invokeAndWait(panel::dispose);}
    }
    @Test void newConversationKeepsPreviousCardsAndDraftsReachable() throws Exception {
        var panel=create((q,s)->ModelResponse.complete("Archived answer."),new AtomicInteger());
        try {
            submit(panel,"Original topic");await(()->!find(panel,TurnCard.class).isEmpty()&&find(panel,TurnCard.class).getFirst().answer!=null);
            SwingUtilities.invokeAndWait(()->button(panel,"New conversation").doClick());
            assertTrue(find(panel,TurnCard.class).isEmpty());
            SwingUtilities.invokeAndWait(()->find(panel,JComboBox.class).stream().filter(b->"Conversation history".equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().setSelectedIndex(0));
            assertEquals(1,find(panel,TurnCard.class).size());assertEquals("Archived answer.",find(panel,TurnCard.class).getFirst().answer.prose());
        } finally {SwingUtilities.invokeAndWait(panel::dispose);}
    }
    private static void submit(CourseMateChatPanel panel,String question)throws Exception{
        SwingUtilities.invokeAndWait(()->{find(panel,JTextArea.class).stream().filter(a->"Question".equals(a.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().setText(question);button(panel,"Send").doClick();});
    }
    private static JButton button(Container c,String name){return find(c,JButton.class).stream().filter(b->b.getText().equals(name)).findFirst().orElseThrow();}
    private static <T> List<T> find(Container c,Class<T> type){List<T> result=new ArrayList<>();for(Component child:c.getComponents()){if(type.isInstance(child))result.add(type.cast(child));if(child instanceof Container container)result.addAll(find(container,type));}return result;}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){AtomicBoolean ok=new AtomicBoolean();SwingUtilities.invokeAndWait(()->ok.set(condition.getAsBoolean()));if(ok.get())return;Thread.sleep(20);}fail("UI operation did not finish within 10 seconds");}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
}
