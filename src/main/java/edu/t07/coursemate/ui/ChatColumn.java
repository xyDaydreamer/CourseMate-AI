package edu.t07.coursemate.ui;

import javax.swing.*;
import java.awt.*;

/** Measures wrapped content at the actual available width before placing following controls. */
class ChatColumn extends JPanel {
    ChatColumn() { this(6); }
    ChatColumn(int gap) { setLayout(new Stack(gap)); setAlignmentX(LEFT_ALIGNMENT); setOpaque(false); }
    int heightAt(int width) { return ((Stack)getLayout()).measure(this, width).height; }
    static int childHeight(Component child, int width) {
        width = Math.max(40, width);
        if (child instanceof ChatColumn column) return column.heightAt(width);
        if (child instanceof RichText text) return text.heightAt(width);
        if (child instanceof JTextArea area && area.getLineWrap()) {
            area.setSize(width, Short.MAX_VALUE); return area.getPreferredSize().height;
        }
        if (child instanceof JPanel panel && panel.getLayout() instanceof FlowLayout flow) {
            Insets in=panel.getInsets();int x=0,y=0,row=0,limit=Math.max(40,width-in.left-in.right);
            for(Component c:panel.getComponents())if(c.isVisible()){
                Dimension d=c.getPreferredSize();if(x>0&&x+d.width>limit){y+=row+flow.getVgap();x=0;row=0;}
                x+=d.width+flow.getHgap();row=Math.max(row,d.height);
            }
            return in.top+in.bottom+y+row+2*flow.getVgap();
        }
        return child.getPreferredSize().height;
    }
    private record Stack(int gap) implements LayoutManager {
        @Override public void addLayoutComponent(String name,Component c) { }
        @Override public void removeLayoutComponent(Component c) { }
        Dimension measure(Container p,int width) {
            Insets in=p.getInsets();int content=Math.max(40,width-in.left-in.right),height=in.top+in.bottom,count=0;
            for(Component c:p.getComponents())if(c.isVisible()){height+=childHeight(c,content);count++;}
            return new Dimension(width,height+Math.max(0,count-1)*gap);
        }
        @Override public Dimension preferredLayoutSize(Container p) { return measure(p,p.getWidth()>40?p.getWidth():480); }
        @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(160,0); }
        @Override public void layoutContainer(Container p) {
            Insets in=p.getInsets();int width=Math.max(40,p.getWidth()-in.left-in.right),y=in.top;
            for(Component c:p.getComponents())if(c.isVisible()){
                int height=childHeight(c,width);c.setBounds(in.left,y,width,height);y+=height+gap;
            }
        }
    }
}
