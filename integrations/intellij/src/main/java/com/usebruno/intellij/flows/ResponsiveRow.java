package com.usebruno.intellij.flows;

import javax.swing.*;
import java.awt.*;
import com.intellij.util.ui.JBUI;

/** Keeps both controls attached while changing from columns to rows. */
final class ResponsiveRow extends JPanel {
    private final JComponent first,second;
    ResponsiveRow(JComponent first,JComponent second) {
        this.first=first; this.second=second; add(first); add(second);
    }
    private boolean stacked() {
        int width=getWidth()>0 ? getWidth() : getParent()==null ? 0 : getParent().getWidth();
        return width>0 && width<JBUI.scale(550);
    }
    @Override public Dimension getPreferredSize() {
        var a=first.getPreferredSize(); var b=second.getPreferredSize(); var insets=getInsets(); int gap=JBUI.scale(4);
        return new Dimension((stacked() ? Math.max(a.width,b.width) : a.width+b.width+gap)+insets.left+insets.right,
                (stacked() ? a.height+b.height+gap : Math.max(a.height,b.height))+insets.top+insets.bottom);
    }
    @Override public Dimension getMinimumSize() { return new Dimension(0,getPreferredSize().height); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
    @Override public void setBounds(int x,int y,int width,int height) {
        boolean resized=width!=getWidth(); super.setBounds(x,y,width,height); if (resized) {
            // BoxLayout caches child sizes, including while the hierarchy is still detached.
            for (Container parent=getParent();parent!=null;parent=parent.getParent()) parent.invalidate();
            revalidate();
        }
    }
    @Override public void doLayout() {
        var insets=getInsets(); int width=Math.max(0,getWidth()-insets.left-insets.right),gap=JBUI.scale(4);
        int a=first.getPreferredSize().height,b=second.getPreferredSize().height;
        if (stacked()) {
            first.setBounds(insets.left,insets.top,width,a);
            second.setBounds(insets.left,insets.top+a+gap,width,b);
        } else {
            int right=Math.min(second.getPreferredSize().width,Math.max(0,(width-gap)/2));
            first.setBounds(insets.left,insets.top,Math.max(0,width-right-gap),Math.max(a,b));
            second.setBounds(insets.left+width-right,insets.top,right,Math.max(a,b));
        }
    }
}
