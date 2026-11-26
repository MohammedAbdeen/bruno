package com.usebruno.intellij.flows;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;

public class FlowRunViewTest extends BrunoPlatformTestCase {
    public void testRunSelectionShowsDistinctLoopStepInputsAndOutputs() throws Exception {
        var previous=UIManager.getLookAndFeel(); boolean wasDark=!com.intellij.ui.JBColor.isBright();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf()); com.intellij.ui.JBColor.setDark(true);
        var history=new FlowRunHistory();
        String report="""
            {"status":"success","context":{"total":90},"steps":[
            {"nodeId":"add","nodeType":"script","status":"success","duration":1,"input":{"context":{"total":0}},"output":{"context":{"total":60}}},
            {"nodeId":"add","nodeType":"script","status":"success","duration":1,"input":{"context":{"total":60}},"output":{"context":{"total":90}}}]}
            """;
        var run=history.add(Instant.now(),"sample.flow.bru","staging",0,2,report,"Flow success: 2 steps");
        var view=new FlowRunView(history); JFrame frame=null;
        try {
            if (Boolean.getBoolean("bruno.guiTests")) { frame=new JFrame("Bruno flow step history"); frame.setContentPane(view); frame.setSize(1000,650); frame.setVisible(true); }
            else view.setSize(1000,650);
            var list=components(view,JList.class).getFirst(); assertEquals(3,list.getModel().getSize());
            assertFalse(list.getModel().getElementAt(1).toString().equals(list.getModel().getElementAt(2).toString()));
            var input=components(view,JTextArea.class).stream().filter(area -> "Step input".equals(area.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            var output=components(view,JTextArea.class).stream().filter(area -> "Step output".equals(area.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            list.setSelectedIndex(1); assertTrue(input.getText().contains("0")); assertTrue(output.getText().contains("60"));
            list.setSelectedIndex(2); assertTrue(input.getText().contains("60")); assertTrue(output.getText().contains("90"));
            if (frame!=null) { frame.validate(); com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); }
            layout(view);
            if (System.getProperty("bruno.screenshotDir")!=null) {
                var image=new java.awt.image.BufferedImage(view.getWidth(),view.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics(); view.paint(graphics); graphics.dispose();
                javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of(System.getProperty("bruno.screenshotDir"),"flow-run-history.png").toFile());
            }
            var failed=history.add(Instant.now(),"failed.flow.bru","",1,2,report.replaceFirst("\"status\":\"success\",\"duration\":1","\"status\":\"failed\",\"duration\":1"),"failed");
            view.refresh(failed); assertEquals("First failed step is selected",1,list.getSelectedIndex());
            history.clear(); view.refresh(null); assertEquals(0,list.getModel().getSize()); assertTrue(output.getText().contains("No run"));
        } finally { if (frame!=null) frame.dispose(); com.intellij.openapi.util.Disposer.dispose(view); history.dispose(); UIManager.setLookAndFeel(previous); com.intellij.ui.JBColor.setDark(wasDark); }
    }
    public void testLargeHistoryLoadsInOneBatchAndLimitsPreviewWithoutTruncatingStoredReport() throws Exception {
        var report=new com.google.gson.JsonObject(); var entries=new com.google.gson.JsonArray();
        for (int i=0;i<1000;i++) {
            var step=new com.google.gson.JsonObject(); step.addProperty("nodeId","node"+i); step.addProperty("status","success");
            var output=new com.google.gson.JsonObject(); output.addProperty("message",i==0 ? "x".repeat(200000) : "result");
            step.add("output",output); entries.add(step);
        }
        report.add("steps",entries);
        var history=new FlowRunHistory(); history.add(Instant.now(),"large.flow.bru","",0,1,report.toString(),"");
        var view=new FlowRunView(history);
        try {
            var list=components(view,JList.class).getFirst();
            assertEquals("Large reports must not parse synchronously on the EDT",0,list.getModel().getSize());
            var batches=new java.util.concurrent.atomic.AtomicInteger();
            list.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
                public void intervalAdded(javax.swing.event.ListDataEvent event) { batches.incrementAndGet(); }
                public void intervalRemoved(javax.swing.event.ListDataEvent event) { }
                public void contentsChanged(javax.swing.event.ListDataEvent event) { }
            });
            waitFor(() -> list.getModel().getSize()==1001);
            var output=components(view,JTextArea.class).stream().filter(area -> "Step output".equals(area.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            waitFor(() -> output.getText().contains("Preview limited"));
            assertEquals(1,batches.get()); assertTrue(output.getText().length()<132000);
            assertTrue(history.runs().getFirst().report().contains("x".repeat(200000)));
            view.refresh(null); history.clear(); view.refresh(null);
            // Drain any cancelled worker callback; it must not restore cleared data.
            for (int i=0;i<10;i++) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            assertEquals(0,list.getModel().getSize()); assertTrue(output.getText().contains("No run"));
        } finally { com.intellij.openapi.util.Disposer.dispose(view); history.dispose(); }
    }
    public void testRunButtonCapturesRealStepIoAndDeletesTemporaryReport() throws Exception {
        var preferences=com.intellij.openapi.application.ApplicationManager.getApplication().getService(BrunoPreferences.class);
        String oldCli=preferences.flowCliPath,oldNode=preferences.nodeExecutable;
        var state=getProject().getService(WorkspaceState.class); var oldCollections=new ArrayList<>(state.flowCollections);
        var history=getProject().getService(FlowRunHistory.class); history.clear();
        var root=java.nio.file.Files.createTempDirectory("bruno-history-test-");
        java.nio.file.Files.createDirectories(root.resolve("flows"));
        java.nio.file.Files.writeString(root.resolve("bruno.json"),"{\"version\":\"1\",\"name\":\"History test\",\"type\":\"collection\"}");
        String source="""
            meta {
              name: IO sample
              type: flow
              seq: 1
            }
            flow {
              data: '''
                {"nodes":[{"id":"start","type":"start","position":{"x":0,"y":100},"data":{"initialVars":{"total":0}}},
                {"id":"script","type":"script","position":{"x":250,"y":100},"data":{"code":"ctx.total = 60;"}},
                {"id":"end","type":"end","position":{"x":500,"y":100},"data":{}}],
                "edges":[{"id":"a","source":"start","target":"script"},{"id":"b","source":"script","target":"end"}],"config":{}}
              '''
            }
            """;
        var flow=root.resolve("flows/io.flow.bru"); java.nio.file.Files.writeString(flow,source);
        preferences.flowCliPath=java.nio.file.Path.of("../..","packages/bruno-cli/bin/bru.js").toAbsolutePath().normalize().toString(); preferences.nodeExecutable="node";
        state.flowCollections.clear(); state.flowCollections.add(root.toString());
        var panel=new BrunoFlowsPanel(getProject()); JFrame frame=null;
        try {
            if (Boolean.getBoolean("bruno.guiTests")) { frame=new JFrame("Bruno real flow run history"); frame.setContentPane(panel); frame.setSize(1100,850); frame.setVisible(true); }
            var pickerField=BrunoFlowsPanel.class.getDeclaredField("flowPicker"); pickerField.setAccessible(true);
            @SuppressWarnings("unchecked") var picker=(JComboBox<FlowFile>)pickerField.get(panel);
            waitFor(() -> picker.getItemCount()>0);
            for (int index=0;index<picker.getItemCount();index++) if (picker.getItemAt(index).path().equals(flow)) picker.setSelectedIndex(index);
            assertEquals(flow,((FlowFile)picker.getSelectedItem()).path());
            components(panel,JButton.class).stream().filter(button -> "Run Flow".equals(button.getText())).findFirst().orElseThrow().doClick();
            waitFor(() -> !history.runs().isEmpty());
            var run=history.runs().getFirst(); assertEquals("success",run.status());
            var summary=com.google.gson.JsonParser.parseString(run.report()).getAsJsonObject();
            var script=summary.getAsJsonArray("steps").get(1).getAsJsonObject();
            assertEquals(0,script.getAsJsonObject("input").getAsJsonObject("context").get("total").getAsInt());
            assertEquals(60,script.getAsJsonObject("output").getAsJsonObject("context").get("total").getAsInt());
            var report=java.util.regex.Pattern.compile("--reporter-json ([^\\r\\n]+)").matcher(run.console());
            assertTrue(report.find()); assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(report.group(1).strip())));
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); layout(panel);
            var list=components(panel,JList.class).getFirst(); assertEquals(4,list.getModel().getSize()); list.setSelectedIndex(2);
            assertTrue(components(panel,JTextArea.class).stream().anyMatch(area -> "Step output".equals(area.getAccessibleContext().getAccessibleName()) && area.getText().contains("60")));
            java.nio.file.Files.writeString(flow,source.replace("\"type\":\"script\"","\"type\":\"delay\"").replace("\"code\":\"ctx.total = 60;\"","\"durationMs\":10000"));
            var runButton=components(panel,JButton.class).stream().filter(button -> "Run Flow".equals(button.getText())).findFirst().orElseThrow();
            waitFor(runButton::isEnabled); runButton.doClick();
            var consoleField=BrunoFlowsPanel.class.getDeclaredField("output"); consoleField.setAccessible(true);
            var console=(JTextArea)consoleField.get(panel);
            var stop=components(panel,JButton.class).stream().filter(button -> "Stop".equals(button.getText())).findFirst().orElseThrow();
            waitFor(() -> console.getText().contains("start (start)") && stop.isEnabled());
            stop.doClick();
            waitFor(() -> history.runs().size()==2);
            assertEquals("cancelled",history.runs().getFirst().status());
            assertEquals("success",history.runs().get(1).status());
        } finally {
            if (frame!=null) frame.dispose(); com.intellij.openapi.util.Disposer.dispose(panel);
            preferences.flowCliPath=oldCli; preferences.nodeExecutable=oldNode; state.flowCollections.clear(); state.flowCollections.addAll(oldCollections); history.clear();
            try (var paths=java.nio.file.Files.walk(root)) { for (var path:paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path); }
        }
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
        assertTrue("Timed out waiting for flow UI",condition.getAsBoolean());
    }
    private static void layout(Container parent) { parent.doLayout(); for (var child:parent.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T extends Component> List<T> components(Container root,Class<T> type) {
        List<T> found=new ArrayList<>(); for (var child:root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container nested) found.addAll(components(nested,type)); } return found;
    }
}
