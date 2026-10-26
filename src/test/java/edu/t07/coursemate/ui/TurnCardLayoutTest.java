package edu.t07.coursemate.ui;

import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.ModelResponse;
import edu.t07.coursemate.chat.*;
import edu.t07.coursemate.rag.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.View;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TurnCardLayoutTest {
    @Test void interactiveLinksChangeColorForHoverPressSelectionAndDisabledState() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Object oldBackground=UIManager.get("Panel.background"),oldLink=UIManager.get("Link.activeForeground"),oldHover=UIManager.get("Link.hoverForeground"),oldPressed=UIManager.get("Link.pressedForeground");
            try{
                for(boolean dark:new boolean[]{false,true}){
                    UIManager.put("Panel.background",dark?new Color(29,31,35):Color.WHITE);
                    UIManager.put("Link.activeForeground",dark?new Color(107,165,255):new Color(35,93,194));
                    UIManager.put("Link.hoverForeground",null);UIManager.put("Link.pressedForeground",null);
                    var request=new ChatSession().prepare("Explain this code.",ChatSession.Code.of("int count=1;"),true,ReplyLanguage.EN);
                    var card=new TurnCard(request,ReplyLanguage.EN,(c,l)->{},s->{});
                    var toggle=findToggle(card);var model=toggle.getModel();Color normal=toggle.getForeground();
                    assertEquals(Cursor.HAND_CURSOR,toggle.getCursor().getType());
                    model.setRollover(true);Color hover=toggle.getForeground();assertNotEquals(normal,hover);
                    model.setArmed(true);model.setPressed(true);Color pressed=toggle.getForeground();assertNotEquals(hover,pressed);assertNotEquals(normal,pressed);
                    model.setPressed(false);model.setArmed(false);model.setRollover(false);toggle.setSelected(false);assertEquals(normal,toggle.getForeground());
                    toggle.doClick();assertTrue(toggle.isSelected());assertNotEquals(normal,toggle.getForeground());
                    toggle.setEnabled(false);Color disabled=toggle.getForeground();model.setRollover(true);assertEquals(disabled,toggle.getForeground());assertEquals(Cursor.DEFAULT_CURSOR,toggle.getCursor().getType());
                }
            }catch(Exception e){throw new RuntimeException(e);}finally{UIManager.put("Panel.background",oldBackground);UIManager.put("Link.activeForeground",oldLink);UIManager.put("Link.hoverForeground",oldHover);UIManager.put("Link.pressedForeground",oldPressed);}
        });
    }
    private static JToggleButton findToggle(Container c){for(Component child:c.getComponents()){if(child instanceof JToggleButton toggle)return toggle;if(child instanceof Container nested){var found=findToggle(nested);if(found!=null)return found;}}return null;}
    @Test void longChineseAnswersNeverCoverButtonsAtNarrowWidthsOrLargeFonts() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Font oldFont=UIManager.getFont("Label.font");Object oldForeground=UIManager.get("Label.foreground"),oldMuted=UIManager.get("Label.disabledForeground"),oldSeparator=UIManager.get("Separator.foreground");
            try{
                for(int fontSize:new int[]{13,22})for(int width:new int[]{340,800}){
                    UIManager.put("Label.font",new Font(Font.SANS_SERIF,Font.PLAIN,fontSize));UIManager.put("Label.foreground",new Color(220,223,228));UIManager.put("Label.disabledForeground",new Color(150,157,167));UIManager.put("Separator.foreground",new Color(65,70,79));
                    var request=new ChatSession().prepare("为什么父类引用能调用子类重写的方法？",null,true,ReplyLanguage.ZH);
                    var chunk=new CourseChunk("Lec-2-OOP-with-Java.pdf",22,"Polymorphism uses virtual method invocation.");
                    String paragraph="变量的声明类型与对象的运行时类型不同，实例方法会根据运行时对象进行动态分派。".repeat(10);
                    var result=new AnswerResult(request.id(),List.of(new AnswerResult.Claim(paragraph,List.of("S1")),new AnswerResult.Claim("```java\nAnimal a = new Dog();\na.speak();\n```\n"+paragraph,List.of("S1"))),"static 方法和字段访问需要分别讨论。".repeat(8),List.of(new AnswerResult.Source("S1",chunk)),new RetrievalResult(RetrievalResult.Status.READY,"polymorphism",List.of(),List.of(chunk),List.of(),"v3"),AnswerResult.Completion.COMPLETE,ModelResponse.complete(""),List.of());
                    var card=new TurnCard(request,ReplyLanguage.ZH,(c,l)->{},s->{});card.complete(result);card.setBackground(new Color(29,31,35));card.setOpaque(true);
                    card.setSize(width,card.heightAt(width));layout(card);assertStackLayout(card);
                    assertEquals(1,countText(card,"课程解答"));
                    assertFalse(buttonTexts(card).stream().anyMatch(t->t.contains("显示本语言")||t.contains("Show answer")));
                    if(fontSize==13&&width==800||fontSize==22&&width==340){
                        BufferedImage image=new BufferedImage(width,card.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();card.printAll(g);g.dispose();
                        try{Path out=Path.of("build/reports/ui/fix-051-"+width+"-font"+fontSize+".png");Files.createDirectories(out.getParent());ImageIO.write(image,"png",out.toFile());}catch(Exception e){throw new RuntimeException(e);}
                    }
                }
            }catch(Exception e){throw new RuntimeException(e);}finally{UIManager.put("Label.font",oldFont);UIManager.put("Label.foreground",oldForeground);UIManager.put("Label.disabledForeground",oldMuted);UIManager.put("Separator.foreground",oldSeparator);}
        });
    }
    private static void assertStackLayout(Container p){
        int bottom=0;
        for(Component child:p.getComponents())if(child.isVisible()){
            if(p instanceof ChatColumn){assertTrue(child.getY()>=bottom,"Following control overlaps previous content");bottom=child.getY()+child.getHeight();}
            if(child instanceof RichText text){float rendered=text.getUI().getRootView(text).getPreferredSpan(View.Y_AXIS);assertTrue(text.getHeight()+1>=rendered,"HTML paint height exceeds allocated height");}
            if(child instanceof Container nested)assertStackLayout(nested);
        }
    }
    private static int countText(Container p,String text){int count=0;for(Component c:p.getComponents()){if(c instanceof RichText r&&documentText(r).contains(text))count++;if(c instanceof Container nested)count+=countText(nested,text);}return count;}
    private static String documentText(RichText text){try{return text.getDocument().getText(0,text.getDocument().getLength());}catch(javax.swing.text.BadLocationException e){throw new AssertionError(e);}}
    private static List<String> buttonTexts(Container p){List<String> out=new ArrayList<>();for(Component c:p.getComponents()){if(c instanceof JButton b)out.add(b.getText());if(c instanceof Container nested)out.addAll(buttonTexts(nested));}return out;}
    private static void layout(Container p){p.doLayout();for(Component c:p.getComponents())if(c.isVisible()&&c instanceof Container nested)layout(nested);}
}
