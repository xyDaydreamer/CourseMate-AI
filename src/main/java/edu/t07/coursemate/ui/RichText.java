package edu.t07.coursemate.ui;

import javax.swing.*;
import javax.swing.text.View;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;

/** Synchronous, theme-aware HTML text with explicit width-dependent height. No external content is loaded. */
final class RichText extends JEditorPane {
    RichText(String markdown, boolean muted) {
        setContentType("text/html");setEditable(false);setOpaque(false);setBorder(null);
        putClientProperty(HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);
        Font base=UIManager.getFont("Label.font");if(base==null)base=new Font(Font.SANS_SERIF,Font.PLAIN,13);
        setFont(muted?base.deriveFont(Math.max(11f,base.getSize2D()-1)):base);
        Color color=muted?UIManager.getColor("Label.disabledForeground"):UIManager.getColor("Label.foreground");
        if(color==null)color=muted?Color.GRAY:Color.BLACK;setForeground(color);
        ((HTMLDocument)getDocument()).setAsynchronousLoadPriority(-1);
        String foreground=String.format("#%02x%02x%02x",color.getRed(),color.getGreen(),color.getBlue());
        setText("<html><head><style>body{margin:0;color:"+foreground+";}p{margin:3px 0 7px 0;}pre{margin:6px 0;padding:6px;font-family:monospace;}h1,h2,h3{margin:8px 0 4px 0;}li{margin:2px 0;}</style></head><body>"+ChatMarkdown.toHtml(markdown)+"</body></html>");
        setAlignmentX(LEFT_ALIGNMENT);
    }
    int heightAt(int width) {
        Insets in=getInsets();int available=Math.max(20,width-in.left-in.right);
        View root=getUI().getRootView(this);root.setSize(available,0);
        return Math.max(getFontMetrics(getFont()).getHeight(),(int)Math.ceil(root.getPreferredSpan(View.Y_AXIS)))+in.top+in.bottom+6;
    }
    @Override public Dimension getPreferredSize(){int width=getWidth()>40?getWidth():480;return new Dimension(width,heightAt(width));}
}
