package com.usebruno.intellij.flows;

import javax.swing.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import com.intellij.ui.EditorTextField;

public class SerenaReviewTest extends BrunoPlatformTestCase {
    public void testResponseJobsAreCancelledAndDisposedViewReleasesDocuments() throws Exception {
        var view=new ResponseView(getProject());
        var taskField=ResponseView.class.getDeclaredField("renderTask"); taskField.setAccessible(true);
        var obsolete=new FutureTask<Void>(() -> null);
        taskField.set(view,obsolete);
        try {
            view.show(new ExecutionResult(0,"Connection error",1,0,"latest error","","",false,false,""));
            assertTrue("Switching responses cancels obsolete formatting",obsolete.isCancelled());
            var pending=new FutureTask<Void>(() -> null); taskField.set(view,pending);
            view.disposeView();
            assertTrue("Closing the view cancels pending work",pending.isCancelled());
            view.show(new ExecutionResult(200,"HTTP 200",1,2,"{}","","",false,false,""));
            for (var field:components(view,EditorTextField.class)) assertEquals("",field.getText());
        } finally { view.disposeView(); }
    }
    public void testDeepResponseJsonFallsBackToRawWithoutCrashingRenderer() throws Exception {
        var view=new ResponseView(getProject());
        String raw="{\"x\":".repeat(1000)+"0"+"}".repeat(1000);
        try {
            view.show(new ExecutionResult(200,"HTTP 200",1,raw.length(),raw,"content-type: application/json","",false,false,"").withPayload("application/json",new byte[0]));
            waitFor(() -> components(view,JLabel.class).stream().anyMatch(label -> label.getText()!=null && label.getText().startsWith("Preview unavailable")));
            assertTrue(components(view,EditorTextField.class).stream().anyMatch(field -> field.getText().equals(raw)));
        } finally { view.disposeView(); }
    }
    public void testStopBeforeProcessCreationPreventsLaunchAndRecordsCancellation() throws Exception {
        var history=getProject().getService(FlowRunHistory.class); history.clear();
        var panel=new BrunoFlowsPanel(getProject());
        try {
            var busy=BrunoFlowsPanel.class.getDeclaredField("executing"); busy.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean)busy.get(panel)).set(true);
            var stop=BrunoFlowsPanel.class.getDeclaredMethod("stopFlow"); stop.setAccessible(true); stop.invoke(panel);
            var cancelled=BrunoFlowsPanel.class.getDeclaredField("stopRequested"); cancelled.setAccessible(true);
            assertTrue("Stop must work before a process exists",cancelled.getBoolean(panel));
            var execute=BrunoFlowsPanel.class.getDeclaredMethod("execute",FlowFile.class,String.class); execute.setAccessible(true);
            var root=Path.of(getProject().getBasePath()).toAbsolutePath();
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread=new Thread(() -> {
                try { execute.invoke(panel,new FlowFile(root.resolve("not-launched.flow.bru"),root),""); }
                catch (Throwable error) { failure.set(error); }
            });
            thread.start(); thread.join(5000); assertFalse(thread.isAlive()); assertNull(failure.get());
            waitFor(() -> !history.runs().isEmpty());
            assertEquals("cancelled",history.runs().getFirst().status());
            assertFalse(history.runs().getFirst().console().contains("$ "));
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)busy.get(panel)).get());
        } finally { panel.dispose(); history.clear(); }
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
        assertTrue("Timed out waiting for response rendering",condition.getAsBoolean());
    }
    private static <T extends java.awt.Component> List<T> components(java.awt.Container parent,Class<T> type) {
        var found=new ArrayList<T>();
        for (var child:parent.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof java.awt.Container nested) found.addAll(components(nested,type));
        }
        return found;
    }
}
