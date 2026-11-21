package com.usebruno.intellij.flows;

import com.intellij.ide.ui.laf.darcula.ui.DarculaTabbedPaneUI;
import javax.swing.*;
import java.awt.*;

public class TabLayoutRegressionTest extends BrunoPlatformTestCase {
    public void testOverflowingTabs() {
        var tabs = new BrunoTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.updateUI();
        assertTrue(tabs.getUI() instanceof javax.swing.plaf.basic.BasicTabbedPaneUI);
        assertFalse(tabs.getUI() instanceof DarculaTabbedPaneUI);
        for (int i = 0; i < 8; i++) {
            tabs.addTab("Request " + i, new JPanel());
            var title = new JPanel(); title.add(new JLabel("GET /api/long-request-path-" + i)); title.add(new JButton("x"));
            tabs.setTabComponentAt(i, title);
        }
        var root = new JPanel(new BorderLayout()); root.add(tabs);
        JFrame frame = null;
        if (Boolean.getBoolean("bruno.guiTests")) {
            frame = new JFrame("Bruno tab layout regression");
            frame.setContentPane(root); frame.setSize(220, 400); frame.setVisible(true);
        } else { root.setSize(220, 400); root.addNotify(); }
        try {
            for (int i = 0; i < 8; i++) {
                tabs.setSelectedIndex(i); root.invalidate(); root.validate();
            }
        } finally { if (frame != null) frame.dispose(); else root.removeNotify(); }
    }
    public void testLiveWorkspaceNavigationAndOverflow() throws Exception {
        var previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf());
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        for (int i = 0; i < 5; i++) {
            var draft = new RequestDraft(); draft.name = "/api/long-controller-path/request-" + i;
            draft.save(); state.drafts.add(draft);
        }
        var panel = new ApiExplorerPanel(getProject());
        JFrame frame = null;
        try {
            panel.stopScan();
            if (Boolean.getBoolean("bruno.guiTests")) {
                frame = new JFrame("Bruno workspace regression"); frame.setContentPane(panel);
                frame.setSize(1200, 850); frame.setVisible(true);
            } else { panel.setSize(1200, 850); panel.addNotify(); }
            for (int pass = 0; pass < 3; pass++) for (int width : new int[]{1200, 420, 550, 240}) {
                if (frame != null) frame.setSize(width, 850); else panel.setSize(width, 850);
                panel.dispatchEvent(new java.awt.event.ComponentEvent(panel, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
                exercise(panel);
                panel.invalidate(); panel.validate();
                if (frame != null) { frame.invalidate(); frame.validate(); }
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
                if (frame != null) {
                    // Native resize events are not InvocationEvents. Pump the real
                    // AWT queue before judging the responsive layout.
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                    assertResponsiveEditors(panel);
                }
            }
        } finally {
            if (frame != null) frame.dispose(); else panel.removeNotify();
            com.intellij.openapi.util.Disposer.dispose(panel); state.drafts.clear();
            UIManager.setLookAndFeel(previous);
        }
    }
    private static void assertResponsiveEditors(Container parent) throws Exception {
        for (var child : parent.getComponents()) {
            if (child instanceof RequestEditor editor && editor.isShowing()) {
                var compact=RequestEditor.class.getDeclaredField("compact"); compact.setAccessible(true);
                assertEquals("Visible editor must follow its actual width", editor.getWidth() < com.intellij.util.ui.JBUI.scale(550),compact.getBoolean(editor));
            }
            if (child instanceof Container nested) assertResponsiveEditors(nested);
        }
    }
    private static void exercise(Container parent) {
        for (var child : parent.getComponents()) {
            if (child instanceof JTabbedPane tabs) {
                // A theme refresh must not restore the failing Darcula delegate.
                tabs.updateUI();
                assertTrue(tabs.getUI() instanceof javax.swing.plaf.basic.BasicTabbedPaneUI);
                assertFalse(tabs.getUI() instanceof DarculaTabbedPaneUI);
                for (int i = 0; i < tabs.getTabCount(); i++) { tabs.setSelectedIndex(i); tabs.doLayout(); }
            }
            if (child instanceof Container nested) exercise(nested);
        }
    }

}
