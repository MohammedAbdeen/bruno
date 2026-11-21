package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.UIUtil;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class ValueReadabilityTest extends BrunoPlatformTestCase {
    public void testExpandedValueAppliesExactTextAndCancelPreservesRow() {
        var row=new RequestRow("X-CSRF-TOKEN","abc".repeat(100)); row.description="Keep metadata";
        var editor=new KeyValueEditor(List.of(row),false,false,() -> {});
        editor.table.setRowSelectionInterval(0,0);
        var dialog=editor.expandedValueDialog();
        try {
            var value=components(dialog.createCenterPanel(),JTextArea.class).getFirst();
            assertEquals(row.value,value.getText()); assertTrue(value.getLineWrap()); assertFalse(value.getWrapStyleWord());
            value.setText("replacement".repeat(100)); dialog.doOKAction();
            assertEquals("replacement".repeat(100),editor.rows().getFirst().value);
            assertEquals(row.id,editor.rows().getFirst().id); assertEquals("Keep metadata",editor.rows().getFirst().description);
        } finally { if (!dialog.isDisposed()) dialog.doCancelAction(); }
        var cancelled=editor.expandedValueDialog();
        try {
            components(cancelled.createCenterPanel(),JTextArea.class).getFirst().setText("discard me"); cancelled.doCancelAction();
            assertEquals("replacement".repeat(100),editor.rows().getFirst().value);
        } finally { if (!cancelled.isDisposed()) cancelled.doCancelAction(); }
    }
    public void testUnchangedStoredSecretIsNotLoadedOrOverwritten() {
        var row=new RequestRow("token",""); row.secret=true; row.secretRef="unloaded-secret";
        var editor=new KeyValueEditor(List.of(row),true,true,() -> {}); editor.table.setRowSelectionInterval(0,0);
        var dialog=editor.expandedValueDialog();
        try {
            assertTrue(dialog.getPreferredFocusedComponent() instanceof JPasswordField);
            assertEquals(0,((JPasswordField)dialog.getPreferredFocusedComponent()).getPassword().length);
            dialog.doOKAction();
            assertFalse(editor.rows().getFirst().secretEdited);
            assertEquals("unloaded-secret",editor.rows().getFirst().secretRef);
        } finally { if (!dialog.isDisposed()) dialog.doCancelAction(); }
    }
    public void testInlineSecretCommitAndReorderedColumnsPreserveCredentials() {
        var row=new RequestRow("token",""); row.secret=true; row.secretRef="saved-secret"; row.description="metadata";
        var editor=new KeyValueEditor(List.of(row),true,true,() -> {});
        editor.table.moveColumn(2,3);
        assertTrue(editor.table.editCellAt(0,3));
        assertTrue(editor.table.getEditorComponent() instanceof JPasswordField);
        editor.commit();
        assertFalse("Focusing a stored secret must not replace it with blank text",editor.rows().getFirst().secretEdited);
        assertEquals("saved-secret",editor.rows().getFirst().secretRef);
        assertTrue(editor.table.editCellAt(0,2));
        assertFalse(editor.table.getEditorComponent() instanceof JPasswordField);
        ((JTextField)editor.table.getEditorComponent()).setText("new metadata"); editor.commit();
        assertEquals("new metadata",editor.rows().getFirst().description);
        assertFalse(editor.rows().getFirst().secretEdited);
        assertTrue(editor.table.editCellAt(0,3));
        ((JPasswordField)editor.table.getEditorComponent()).setText("replacement"); editor.commit();
        assertEquals("replacement",editor.rows().getFirst().value);
        assertTrue(editor.rows().getFirst().secretEdited);
    }
    public void testLongResponseTokenSoftWrapsWithoutChangingItsText() throws Exception {
        var preferences=ApplicationManager.getApplication().getService(BrunoPreferences.class);
        boolean previous=preferences.responseSoftWrap; preferences.responseSoftWrap=true;
        var response=new ResponseView(getProject()); JFrame frame=null;
        String token="token123".repeat(400), json="{\"token\":\""+token+"\"}";
        try {
            response.show(new ExecutionResult(200,"HTTP 200",1,json.length(),json,"content-type: application/json","",false,false,""));
            if (Boolean.getBoolean("bruno.guiTests")) {
                frame=new JFrame("Bruno response token wrapping"); frame.setContentPane(response); frame.setSize(700,650); frame.setVisible(true);
            } else { response.setSize(700,650); response.addNotify(); }
            layout(response);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            var body=components(response,EditorTextField.class).getFirst();
            while (!body.getText().contains("  \"token\"") && System.nanoTime()<deadline) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            if (frame!=null) com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            layout(response);
            assertNotNull(body.getEditor()); assertTrue(body.getEditor().getSettings().isUseSoftWraps());
            assertTrue(body.getText().contains(token));
            // Force layout calculation and verify the visual position extends
            // beyond the JSON document's physical lines for an unbroken token.
            var editor=body.getEditor(); editor.offsetToVisualPosition(editor.getDocument().getTextLength());
            assertTrue("Long token must span visual lines",editor.getSoftWrapModel().getSoftWrapsForRange(0,editor.getDocument().getTextLength()).size()>0);
            String text=body.getText();
            var wrap=components(response,JCheckBox.class).stream().filter(box -> "Wrap lines".equals(box.getText())).findFirst().orElseThrow();
            wrap.doClick(); assertFalse(editor.getSettings().isUseSoftWraps()); assertFalse(preferences.responseSoftWrap);
            assertEquals(text,body.getText());
            wrap.doClick(); assertTrue(editor.getSettings().isUseSoftWraps()); assertEquals(text,body.getText());
            if (System.getProperty("bruno.screenshotDir")!=null) {
                var snapshot=new java.awt.image.BufferedImage(response.getWidth(),response.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=snapshot.createGraphics(); response.paint(graphics); graphics.dispose();
                javax.imageio.ImageIO.write(snapshot,"png",java.nio.file.Path.of(System.getProperty("bruno.screenshotDir"),"response-token-wrapped.png").toFile());
            }
        } finally {
            if (frame!=null) frame.dispose(); else response.removeNotify();
            response.disposeView(); preferences.responseSoftWrap=previous;
        }
    }
    private static void layout(Container parent) { parent.doLayout(); for (var child:parent.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T extends Component> List<T> components(Container root,Class<T> type) {
        List<T> result=new ArrayList<>();
        for (var child:root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container nested) result.addAll(components(nested,type)); }
        return result;
    }
}
