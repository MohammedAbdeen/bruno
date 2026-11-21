package com.usebruno.intellij.flows;

import javax.swing.JTabbedPane;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import java.awt.*;

/** Plugin-local layout avoids Darcula's recursive overflow layout. */
final class BrunoTabbedPane extends JTabbedPane {
    private boolean tabsHidden;
    void setTabsHidden(boolean hidden) {
        tabsHidden = hidden;
        setFocusable(!hidden);
        // With no strip, use the local Basic layout without scroll-button children.
        setTabLayoutPolicy(hidden ? WRAP_TAB_LAYOUT : SCROLL_TAB_LAYOUT);
        revalidate(); repaint();
    }
    boolean tabsHidden() { return tabsHidden; }
    @Override public void updateUI() {
        setUI(new BasicTabbedPaneUI() {
            @Override protected int calculateTabAreaHeight(int placement, int runs, int height) {
                return tabsHidden ? 0 : super.calculateTabAreaHeight(placement, runs, height);
            }
            @Override protected int calculateTabAreaWidth(int placement, int runs, int width) {
                return tabsHidden ? 0 : super.calculateTabAreaWidth(placement, runs, width);
            }
            @Override protected void paintTabArea(Graphics g, int placement, int selected) {
                if (!tabsHidden) super.paintTabArea(g, placement, selected);
            }
            @Override public int tabForCoordinate(JTabbedPane pane, int x, int y) {
                return tabsHidden ? -1 : super.tabForCoordinate(pane, x, y);
            }
            @Override protected void installDefaults() {
                super.installDefaults();
                tabInsets = JBUI.insets(5, 10);
                contentBorderInsets = JBUI.insets(1, 0, 0, 0);
            }
            @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
                g.setColor(selected ? JBColor.namedColor("List.selectionInactiveBackground", new JBColor(0xDCE6F2, 0x393B40)) : tabPane.getBackground());
                g.fillRect(x, y, w, h);
            }
            @Override protected void paintTabBorder(Graphics g, int p, int i, int x, int y, int w, int h, boolean selected) {
                if (selected) {
                    g.setColor(JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7)));
                    g.fillRect(x, y + h - JBUI.scale(2), w, JBUI.scale(2));
                }
            }
            @Override protected void paintText(Graphics g, int p, Font font, FontMetrics metrics, int index, String title, Rectangle text, boolean selected) {
                g.setFont(font); g.setColor(tabPane.getForegroundAt(index));
                g.drawString(title, text.x, text.y + metrics.getAscent());
            }
            @Override protected void paintContentBorder(Graphics g, int placement, int selected) { }
            @Override protected void paintFocusIndicator(Graphics g, int p, Rectangle[] rectangles, int i, Rectangle icon, Rectangle text, boolean selected) { }
        });
        setForeground(JBColor.namedColor("Label.foreground", JBColor.foreground()));
        setBackground(JBColor.namedColor("Panel.background", JBColor.background()));
    }
}
