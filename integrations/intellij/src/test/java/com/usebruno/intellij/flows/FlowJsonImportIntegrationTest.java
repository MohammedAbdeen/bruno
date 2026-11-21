package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.UIUtil;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.*;
import java.util.List;

public class FlowJsonImportIntegrationTest extends BrunoPlatformTestCase {
    private Path temporary;
    @Override protected void tearDown() throws Exception {
        try { if (temporary!=null) com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile()); } finally { super.tearDown(); }
    }
    private VirtualFile folder(String name) throws Exception {
        if (temporary==null) temporary=Files.createTempDirectory("bruno-json-import-");
        var path=Files.createDirectories(temporary.resolve(name));
        return WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
    }
    public void testBruAndYamlImportsRunAndKeepExistingFilesAndQuotedScripts() throws Exception {
        for (boolean yaml:new boolean[]{false,true}) {
            var root=folder(yaml ? "yaml" : "bru");
            if (yaml) WriteAction.run(() -> VfsUtil.saveText(root.createChildData(this,"opencollection.yml"),"opencollection: 1.0.0\ninfo:\n  name: Import test\n"));
            var document=JsonParser.parseString(FlowJsonImportTest.sample()).getAsJsonObject();
            var script=document.getAsJsonObject("flow").getAsJsonArray("nodes").get(2).getAsJsonObject().getAsJsonObject("data");
            script.addProperty("code",script.get("code").getAsString()+"\nctx.quoted = \"'''\";");
            var prepared=FlowJsonImport.prepare(document.toString(),Path.of(root.getPath()),yaml);
            var imported=FlowFileOperations.importJson(getProject(),root,prepared); String original=VfsUtil.loadText(imported);
            assertTrue(imported.getName().endsWith(yaml ? ".flow.yml" : ".flow.bru"));
            var duplicate=FlowFileOperations.importJson(getProject(),root,prepared);
            assertFalse(imported.getPath().equals(duplicate.getPath())); assertEquals(original,VfsUtil.loadText(imported));
            assertEquals(yaml,root.findChild("bruno.json")==null);
            Path report=temporary.resolve(yaml ? "yaml-report.json" : "bru-report.json"),log=temporary.resolve(yaml ? "yaml.log" : "bru.log");
            var preferences=new BrunoPreferences(); preferences.flowCliPath=Path.of("../..","packages/bruno-cli/bin/bru.js").toAbsolutePath().normalize().toString();
            var command=BrunoCli.command(null,new FlowFile(Path.of(imported.getPath()),Path.of(root.getPath())),"",preferences);
            command.addAll(List.of("--reporter-json",report.toString()));
            var process=new ProcessBuilder(command).directory(Path.of(root.getPath()).toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(Files.readString(log),0,process.exitValue());
                var summary=JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonObject("summary");
                assertEquals(60,summary.getAsJsonObject("context").get("total").getAsInt());
                assertEquals("'''",summary.getAsJsonObject("context").get("quoted").getAsString());
            } finally { process.destroyForcibly(); }
        }
    }
    public void testPanelImportsSelectsAndRunsSampleWithoutOverwritingOrAutoExecuting() throws Exception {
        var root=folder("collection"); var sourceFolder=folder("input");
        var source=WriteAction.compute(() -> { var file=sourceFolder.createChildData(this,"test.json"); VfsUtil.saveText(file,FlowJsonImportTest.sample()); return file; });
        var preferences=ApplicationManager.getApplication().getService(BrunoPreferences.class);
        String oldCli=preferences.flowCliPath,oldNode=preferences.nodeExecutable;
        preferences.flowCliPath=Path.of("../..","packages/bruno-cli/bin/bru.js").toAbsolutePath().normalize().toString(); preferences.nodeExecutable="node";
        var state=getProject().getService(WorkspaceState.class); var oldCollections=new ArrayList<>(state.flowCollections);
        var history=getProject().getService(FlowRunHistory.class); history.clear();
        var previousLook=UIManager.getLookAndFeel(); boolean previousDark=!com.intellij.ui.JBColor.isBright();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf()); com.intellij.ui.JBColor.setDark(true);
        var panel=new BrunoFlowsPanel(getProject()); JFrame frame=null;
        try {
            if (Boolean.getBoolean("bruno.guiTests")) { frame=new JFrame("Bruno JSON import"); frame.setContentPane(panel); frame.setSize(1050,800); frame.setVisible(true); }
            assertEquals(2,components(panel,JButton.class).stream().filter(button -> "Import JSON…".equals(button.getText())).count());
            panel.importFlowJson(source,root);
            var field=BrunoFlowsPanel.class.getDeclaredField("flowPicker"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var picker=(JComboBox<FlowFile>)field.get(panel);
            waitFor(() -> picker.getSelectedItem() instanceof FlowFile flow && flow.path().startsWith(Path.of(root.getPath())));
            var first=(FlowFile)picker.getSelectedItem();
            assertTrue(Files.exists(first.path())); assertTrue(history.runs().isEmpty());
            assertEquals(FlowJsonImportTest.sample(),VfsUtil.loadText(source));
            components(panel,JButton.class).stream().filter(button -> "Run Flow".equals(button.getText())).findFirst().orElseThrow().doClick();
            waitFor(() -> !history.runs().isEmpty()); assertEquals("success",history.runs().getFirst().status());
            var summary=JsonParser.parseString(history.runs().getFirst().report()).getAsJsonObject(); assertEquals(60,summary.getAsJsonObject("context").get("total").getAsInt());
            UIUtil.dispatchAllInvocationEvents();
            if (frame!=null) {
                frame.validate(); com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                String screenshots=System.getProperty("bruno.screenshotDir");
                if (screenshots!=null) {
                    var image=new java.awt.image.BufferedImage(panel.getWidth(),panel.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics=image.createGraphics(); panel.paint(graphics); graphics.dispose();
                    javax.imageio.ImageIO.write(image,"png",Path.of(screenshots,"flow-json-import.png").toFile());
                }
            }
            panel.importFlowJson(source,root);
            waitFor(() -> picker.getSelectedItem() instanceof FlowFile flow && !flow.path().equals(first.path()));
            assertTrue(Files.exists(first.path())); assertEquals(1,history.runs().size());
        } finally {
            var editors=com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject());
            for (var file:editors.getOpenFiles()) if (VfsUtil.isAncestor(root,file,false)) editors.closeFile(file);
            if (frame!=null) frame.dispose(); Disposer.dispose(panel);
            preferences.flowCliPath=oldCli; preferences.nodeExecutable=oldNode;
            state.flowCollections.clear(); state.flowCollections.addAll(oldCollections); history.clear();
            UIManager.setLookAndFeel(previousLook); com.intellij.ui.JBColor.setDark(previousDark);
        }
    }
    public void testInvalidImportLeavesDestinationUnchanged() throws Exception {
        var root=folder("empty"); var input=folder("invalid-input");
        var source=WriteAction.compute(() -> { var file=input.createChildData(this,"invalid.json"); VfsUtil.saveText(file,"{\"nodes\":\"invalid\",\"edges\":[]}"); return file; });
        var panel=new BrunoFlowsPanel(getProject());
        try {
            panel.importFlowJson(source,root);
            var field=BrunoFlowsPanel.class.getDeclaredField("importing"); field.setAccessible(true);
            var flag=(java.util.concurrent.atomic.AtomicBoolean)field.get(panel); waitFor(() -> !flag.get());
            assertNull(root.findChild("flows")); assertNull(root.findChild("bruno.json"));
        } finally { Disposer.dispose(panel); }
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime()<end) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
        assertTrue("Timed out waiting for import",condition.getAsBoolean());
    }
    private static <T extends Component> List<T> components(Container root,Class<T> type) {
        List<T> found=new ArrayList<>(); for (var child:root.getComponents()) { if (type.isInstance(child)) found.add(type.cast(child)); if (child instanceof Container nested) found.addAll(components(nested,type)); } return found;
    }
}
