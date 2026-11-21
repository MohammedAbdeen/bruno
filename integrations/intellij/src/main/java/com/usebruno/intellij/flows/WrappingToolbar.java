package com.usebruno.intellij.flows;

import javax.swing.*;
import java.awt.*;
import com.intellij.util.ui.JBUI;

/** FlowLayout already wraps controls; its preferred height must describe those rows too. */
final class WrappingToolbar extends JPanel {
    WrappingToolbar(int alignment, Component... controls) {
        super(new Rows(alignment));
        for (var control:controls) add(control);
    }
    @Override public void setBounds(int x,int y,int width,int height) {
        boolean resized=width!=getWidth(); super.setBounds(x,y,width,height);
        if (resized) {
            // BoxLayout caches child sizes, including while the hierarchy is still detached.
            for (Container parent=getParent();parent!=null;parent=parent.getParent()) parent.invalidate();
            revalidate();
        }
    }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
    private static final class Rows extends FlowLayout {
        Rows(int alignment) { super(alignment,JBUI.scale(4),JBUI.scale(3)); }
        @Override public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                int width=target.getWidth();
                if (width<=0 && target.getParent()!=null) width=target.getParent().getWidth();
                if (width<=0) return super.preferredLayoutSize(target);
                var insets=target.getInsets();
                int available=Math.max(1,width-insets.left-insets.right-2*getHgap());
                int rowWidth=0,rowHeight=0,maxWidth=0,height=0; boolean populated=false;
                for (var control:target.getComponents()) if (control.isVisible()) {
                    var size=control.getPreferredSize();
                    if (populated && rowWidth+getHgap()+size.width>available) {
                        height+=rowHeight+getVgap(); maxWidth=Math.max(maxWidth,rowWidth);
                        rowWidth=0; rowHeight=0; populated=false;
                    }
                    rowWidth+=(populated ? getHgap() : 0)+size.width;
                    rowHeight=Math.max(rowHeight,size.height); populated=true;
                }
                height+=rowHeight; maxWidth=Math.max(maxWidth,rowWidth);
                return new Dimension(maxWidth+insets.left+insets.right+2*getHgap(),height+insets.top+insets.bottom+2*getVgap());
            }
        }
        @Override public Dimension minimumLayoutSize(Container target) { return new Dimension(0,preferredLayoutSize(target).height); }
    }
}
