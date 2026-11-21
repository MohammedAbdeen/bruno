package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.ui.JBSplitter;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Every invocation remains a separate row, including repeated loop/subflow nodes. */
final class FlowRunView extends JPanel implements com.intellij.openapi.Disposable {
    private static final Gson PRETTY=new GsonBuilder().setPrettyPrinting().create();
    private final FlowRunHistory history;
    private final JComboBox<FlowRunHistory.Run> picker=new JComboBox<>();
    private final DefaultListModel<Step> steps=new DefaultListModel<>();
    private final JList<Step> list=new JList<>(steps);
    private final JTextArea input=text(),output=text(),details=text();
    private boolean changing,disposed;
    private long generation,previewGeneration;
    private java.util.concurrent.Future<?> loadTask,previewTask;
    private record Step(String name,JsonObject data) { @Override public String toString() { return name; } }
    FlowRunView(FlowRunHistory history) {
        super(new BorderLayout(JBUI.scale(6),JBUI.scale(6))); this.history=history;
        picker.setPrototypeDisplayValue(new FlowRunHistory.Run("",java.time.Instant.now(),"Example.flow.bru","","success",123,"{}",""));
        picker.setToolTipText("Project-session run history: latest 10 runs within 5 MiB");
        picker.addActionListener(event -> { if (!changing) selectRun(); });
        var top=new JPanel(new BorderLayout(JBUI.scale(4),0)); top.add(BrunoUi.labeled("Run history",picker),BorderLayout.CENTER);
        top.add(BrunoUi.button("Clear history",null,() -> { history.clear(); refresh(null); }),BorderLayout.EAST); add(top,BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.getAccessibleContext().setAccessibleName("Executed flow steps");
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) selectStep(); });
        var tabs=new BrunoTabbedPane(); tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Input",new JScrollPane(input)); tabs.addTab("Output",new JScrollPane(output)); tabs.addTab("Details",new JScrollPane(details));
        input.getAccessibleContext().setAccessibleName("Step input"); output.getAccessibleContext().setAccessibleName("Step output"); details.getAccessibleContext().setAccessibleName("Step details");
        var split=new JBSplitter(false,0.30f) {
            @Override public void doLayout() {
                boolean vertical=getWidth()<JBUI.scale(550); if (getOrientation()!=vertical) setOrientation(vertical);
                super.doLayout();
            }
        }; split.setHonorComponentsMinimumSize(false); split.setDividerWidth(JBUI.scale(5));
        split.setFirstComponent(new JScrollPane(list)); split.setSecondComponent(tabs);
        add(split,BorderLayout.CENTER); refresh(null);
    }
    void refresh(FlowRunHistory.Run selected) {
        if (disposed) return;
        changing=true; picker.removeAllItems(); history.runs().forEach(picker::addItem);
        if (selected!=null && history.runs().contains(selected)) picker.setSelectedItem(selected);
        changing=false; selectRun();
    }
    private void selectRun() {
        long token=++generation; ++previewGeneration; cancel(loadTask); cancel(previewTask);
        steps.clear(); var run=(FlowRunHistory.Run)picker.getSelectedItem();
        if (run==null) { input.setText("Run a flow to inspect each step's captured input."); output.setText("No run selected."); details.setText(""); return; }
        input.setText("Loading execution details…"); output.setText(""); details.setText("");
        Runnable load=() -> {
            try {
                var report=FlowRunHistory.summary(run.report());
                var rows=new java.util.ArrayList<Step>(); rows.add(new Step("Run summary · "+run.status(),report));
                appendSteps(report,"",rows);
                Runnable apply=() -> {
                    if (disposed || token!=generation) return;
                    steps.addAll(rows);
                    int selected=rows.size()>1 ? 1 : 0;
                    for (int i=1;i<rows.size();i++) if (java.util.List.of("failed","cancelled").contains(string(rows.get(i).data(),"status"))) { selected=i; break; }
                    list.setSelectedIndex(selected);
                };
                dispatch(run,apply);
            } catch (Exception error) { dispatch(run,() -> { if (!disposed && token==generation) input.setText("Execution details unavailable: "+error.getMessage()); }); }
        };
        if (large(run)) loadTask=com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(load); else load.run();
    }
    private static void appendSteps(JsonObject report,String prefix,java.util.List<Step> target) {
        if (!report.has("steps") || !report.get("steps").isJsonArray()) return;
        int index=0;
        for (var entry:report.getAsJsonArray("steps")) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!entry.isJsonObject()) continue; var step=entry.getAsJsonObject();
            String label=prefix+(++index)+". "+string(step,"nodeId")+" ("+string(step,"nodeType")+") · "+string(step,"status")+" · "+string(step,"duration")+" ms";
            target.add(new Step(label,step));
            if (step.has("subFlow") && step.get("subFlow").isJsonObject()) appendSteps(step.getAsJsonObject("subFlow"),prefix+index+"/",target);
        }
    }
    private void selectStep() {
        var selected=list.getSelectedValue(); if (selected==null) return;
        var run=(FlowRunHistory.Run)picker.getSelectedItem(); if (run==null) return;
        long runToken=generation,preview=++previewGeneration; cancel(previewTask);
        Runnable render=() -> {
            var step=selected.data();
            JsonElement captured=step.get("input");
            if (captured==null && step.has("steps") && step.get("steps").isJsonArray() && !step.getAsJsonArray("steps").isEmpty()) captured=step.getAsJsonArray("steps").get(0).getAsJsonObject().get("input");
            String before=pretty(captured),after=pretty(step.has("output") ? step.get("output") : step.get("context"));
            var metadata=new JsonObject();
            for (String key:java.util.List.of("runId","stepId","nodeId","nodeType","status","duration","error","assertions","conditionMet","delayMs")) if (step.has(key)) metadata.add(key,step.get(key));
            String description=limited("Flow: "+run.flow()+"\nEnvironment: "+(run.environment().isBlank() ? "Collection defaults" : run.environment())
                    +"\nStarted: "+run.started()+"\nTotal elapsed (including CLI startup): "+run.duration()+" ms\n\n"+pretty(metadata)+"\n\nConsole:\n"+run.console());
            dispatch(run,() -> {
                if (disposed || runToken!=generation || preview!=previewGeneration) return;
                input.setText(before); output.setText(after); details.setText(description);
                for (var area:new JTextArea[]{input,output,details}) area.setCaretPosition(0);
            });
        };
        if (large(run)) {
            input.setText("Loading step…"); output.setText(""); details.setText("");
            previewTask=com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(render);
        } else render.run();
    }
    private static boolean large(FlowRunHistory.Run run) { return run.report().length()>=32*1024; }
    private static void dispatch(FlowRunHistory.Run run,Runnable task) { if (large(run)) SwingUtilities.invokeLater(task); else task.run(); }
    private static void cancel(java.util.concurrent.Future<?> task) { if (task!=null) task.cancel(true); }
    @Override public void dispose() {
        disposed=true; ++generation; ++previewGeneration; cancel(loadTask); cancel(previewTask);
        changing=true; picker.removeAllItems(); steps.clear(); input.setText(""); output.setText(""); details.setText("");
    }
    private static String string(JsonObject object,String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private static final class PreviewLimit extends java.io.IOException { }
    private static String pretty(JsonElement value) {
        if (value==null || value.isJsonNull()) return "No captured data.";
        var buffer=new StringBuilder();
        var writer=new java.io.Writer() {
            @Override public void write(char[] chars,int offset,int count) throws java.io.IOException {
                if (Thread.currentThread().isInterrupted()) throw new PreviewLimit();
                int remaining=128*1024-buffer.length(); buffer.append(chars,offset,Math.min(count,remaining));
                if (count>remaining) throw new PreviewLimit();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try { PRETTY.toJson(value,writer); }
        catch (JsonIOException error) { if (!(error.getCause() instanceof PreviewLimit)) throw error; return buffer+"\n[Preview limited to 128K characters]"; }
        return buffer.toString();
    }
    private static String limited(String value) { return value.length()>128*1024 ? value.substring(0,128*1024)+"\n[Preview limited to 128K characters]" : value; }
    private static JTextArea text() { var area=new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(false); area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,JBUI.scale(12))); return area; }
}
