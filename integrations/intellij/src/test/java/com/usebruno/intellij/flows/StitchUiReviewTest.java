package com.usebruno.intellij.flows;

import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.UIUtil;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class StitchUiReviewTest extends BrunoPlatformTestCase {
    private LookAndFeel previousLaf; private boolean previousDark;
    @Override protected void setUp() throws Exception {
        super.setUp(); previousLaf=UIManager.getLookAndFeel(); previousDark=!com.intellij.ui.JBColor.isBright();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf());
        com.intellij.ui.JBColor.setDark(true); com.intellij.openapi.util.IconLoader.activate();
        com.intellij.openapi.util.IconLoader.setUseDarkIcons(true);
    }
    @Override protected void tearDown() throws Exception {
        try {
            UIManager.setLookAndFeel(previousLaf); com.intellij.ui.JBColor.setDark(previousDark);
            com.intellij.openapi.util.IconLoader.setUseDarkIcons(previousDark);
        } finally { super.tearDown(); }
    }
    public void testRequestCommandsShareOneRowAndResolvedUrlDoesNotExpandTheHeader() throws Exception {
        var draft=new RequestDraft(); draft.fields.url="{{baseUrl}}/users"; draft.selectedSection="Auth"; draft.save();
        var editor=new RequestEditor(getProject(),draft,() -> {},action -> {});
        JFrame frame=show(editor,"Request commands");
        waitFor(() -> components(editor,JLabel.class).stream().anyMatch(label -> label.getText()!=null && label.getText().startsWith("Resolved: ")));
        try {
            for (int width:new int[]{420,900}) {
                resize(editor,frame,width,800);
                JButton send=button(editor,"Send"),save=button(editor,"Save"),more=button(editor,"More…");
                for (var control:List.of(send,save,more)) contained(editor,control);
                assertTrue("Commands should share a row",Math.abs(centerY(editor,send)-centerY(editor,save))<8);
                var preview=components(editor,JLabel.class).stream().filter(label -> label.getText()!=null && label.getText().startsWith("Resolved: ")).findFirst().orElseThrow();
                var previewBounds=SwingUtilities.convertRectangle(preview.getParent(),preview.getBounds(),editor);
                assertTrue("Resolved URL should start at the header's left edge",previewBounds.x<12);
                assertTrue("Resolved URL should use the header width",previewBounds.width>=editor.getWidth()-16);
                assertTrue(preview.getText().contains("http://localhost:8080/users"));
                assertEquals("http://localhost:8080/users",preview.getToolTipText());
                screenshot(editor,"stitch-request-"+width+".png");
            }
            var urlField=components(editor,JTextField.class).stream().filter(field -> "{{baseUrl}}/users".equals(field.getText())).findFirst().orElseThrow();
            urlField.setText("{{baseUrl}}/"+"path".repeat(1000)); editor.refreshEnvironments();
            resize(editor,frame,420,800);
            var section=components(editor,JComboBox.class).stream().filter(combo -> "Request section".equals(combo.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            contained(editor,section); assertTrue("Long URLs must not consume the request viewport",section.getHeight()<80);
            assertFalse(draft.fields.url.isBlank());
        } finally { if (frame!=null) frame.dispose(); Disposer.dispose(editor); }
    }
    public void testFlowActionsAndEnvironmentRemainVisibleAtNarrowWidths() throws Exception {
        var state=getProject().getService(WorkspaceState.class); var previous=new ArrayList<>(state.flowCollections);
        Path root=Files.createTempDirectory("bruno-stitch-layout-");
        Files.writeString(root.resolve("bruno.json"),"{\"version\":\"1\",\"type\":\"collection\",\"name\":\"Layout\"}");
        Files.createDirectories(root.resolve("flows"));
        Files.writeString(root.resolve("flows/sample.flow.bru"),"meta {\n name: Sample\n type: flow\n seq: 1\n}\n");
        state.flowCollections.clear(); state.flowCollections.add(root.toString());
        var panel=new BrunoFlowsPanel(getProject()); JFrame frame=show(panel,"Flow actions");
        try {
            var pickerField=BrunoFlowsPanel.class.getDeclaredField("flowPicker"); pickerField.setAccessible(true);
            var picker=(JComboBox<?>)pickerField.get(panel);
            waitFor(() -> picker.getItemCount()>0);
            for (int width:new int[]{320,420,900,320}) {
                resize(panel,frame,width,850);
                for (String text:List.of("Run Flow","Stop","Delete Flow","Clear Output","Import JSON…","Runner settings…")) contained(panel,button(panel,text));
                var env=components(panel,JTextField.class).getFirst();
                contained(panel,env); assertTrue("Environment must remain editable",env.getWidth()>80);
                screenshot(panel,"stitch-flows-"+width+".png");
            }
        } finally {
            if (frame!=null) frame.dispose(); com.intellij.openapi.util.Disposer.dispose(panel); state.flowCollections.clear(); state.flowCollections.addAll(previous);
            try (var paths=Files.walk(root)) { for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    public void testGlobalEnvironmentAndEmptyFlowActionsFitSmallWindows() throws Exception {
        var explorer=new ApiExplorerPanel(getProject()); explorer.stopScan();
        var empty=new BrunoFlowsPanel(getProject());
        try {
            resize(explorer,null,320,700);
            var selector=components(explorer,JComboBox.class).stream().filter(combo -> "Global environment".equals(combo.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            contained(explorer,selector); assertTrue(selector.getWidth()>80);
            var manage=components(explorer,JButton.class).stream().filter(control -> "Manage global environments".equals(control.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            contained(explorer,manage); assertEquals("Manage…",manage.getText());
            resize(explorer,null,900,700); assertEquals("Environments…",manage.getText());
            resize(empty,null,320,700);
            screenshot(empty,"stitch-flows-empty-320.png");
            for (String text:List.of("Create New Flow","Import JSON…","Init Bruno Collection","Refresh")) contained(empty,button(empty,text));
        } finally { Disposer.dispose(explorer); com.intellij.openapi.util.Disposer.dispose(empty); }
    }
    private static JFrame show(JComponent root,String title) {
        if (!Boolean.getBoolean("bruno.guiTests")) return null;
        var frame=new JFrame(title); frame.setContentPane(root); frame.setSize(900,850); frame.setVisible(true); return frame;
    }
    private static void resize(JComponent root,JFrame frame,int width,int height) throws Exception {
        if (frame!=null) frame.setSize(width,height); else root.setSize(width,height);
        for (int pass=0;pass<5;pass++) {
            if (frame!=null) { frame.validate(); com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); }
            layout(root); UIUtil.dispatchAllInvocationEvents();
        }
    }
    private static void contained(JComponent root,JComponent child) {
        var bounds=SwingUtilities.convertRectangle(child.getParent(),child.getBounds(),root);
        assertTrue("Clipped "+child+" at "+bounds+" in "+root.getSize(),child.getWidth()>0 && child.getHeight()>0
                && bounds.x>=0 && bounds.y>=0 && bounds.getMaxX()<=root.getWidth()+1 && bounds.getMaxY()<=root.getHeight()+1);
        // Also catch controls wrapped below their own toolbar's reserved height.
        for (Component parent=child.getParent();parent!=null && parent!=root;parent=parent.getParent()) {
            var local=SwingUtilities.convertRectangle(child.getParent(),child.getBounds(),parent);
            assertTrue("Control "+child+" at "+local+" extends beyond "+parent.getClass().getSimpleName()+" "+parent.getSize(),local.y>=0 && local.getMaxY()<=parent.getHeight()+1);
        }
    }
    private static boolean active(Component child) {
        for (Component current=child;current!=null;current=current.getParent()) if (!current.isVisible()) return false;
        return true;
    }
    private static JButton button(Container root,String text) {
        return components(root,JButton.class).stream().filter(control -> text.equals(control.getText()) && active(control)).findFirst().orElseThrow();
    }
    private static int centerY(JComponent root,JComponent child) { var point=SwingUtilities.convertPoint(child,0,child.getHeight()/2,root); return point.y; }
    private static void layout(Container root) { root.doLayout(); for (var child:root.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime()<until) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
        assertTrue("Timed out waiting for flow discovery",ready.getAsBoolean());
    }
    private static void screenshot(JComponent root,String file) throws Exception {
        if (System.getProperty("bruno.screenshotDir")==null) return;
        var image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics(); root.paint(graphics); graphics.dispose();
        javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("bruno.screenshotDir"),file).toFile());
    }
    private static <T extends Component> List<T> components(Container root,Class<T> type) {
        var result=new ArrayList<T>();
        for (var child:root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container nested) result.addAll(components(nested,type)); }
        return result;
    }
}
