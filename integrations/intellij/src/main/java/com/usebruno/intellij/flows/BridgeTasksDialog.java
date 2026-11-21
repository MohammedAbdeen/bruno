package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

final class BridgeTasksDialog extends DialogWrapper {
    private final Project project;
    private final JComboBox<String> picker = new JComboBox<>();
    private final JTextField parameter = new JTextField();
    private final JTextArea result = new JTextArea();
    private final java.util.List<JsonObject> tasks = new ArrayList<>();
    private boolean busy;
    private volatile HttpClient client;
    private final JButton runButton = BrunoUi.button("Run selected task",null,this::run);
    BridgeTasksDialog(Project project) { super(project,false); this.project=project; setTitle("Bruno Scheduled / XXL Tasks"); getCancelAction().putValue(Action.NAME,"Close"); init(); refresh(); }
    @Override protected Action[] createActions() { return new Action[]{getCancelAction()}; }
    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(6,6)); panel.setPreferredSize(JBUI.size(700,400)); result.setEditable(false); result.setLineWrap(true); result.setWrapStyleWord(true);
        JPanel controls = new JPanel(); controls.setLayout(new BoxLayout(controls,BoxLayout.Y_AXIS));
        controls.add(BrunoUi.labeled("Annotated task",picker)); controls.add(BrunoUi.labeled("XXL job parameter",parameter));
        controls.add(BrunoUi.toolbar(BrunoUi.button("Refresh tasks",null,this::refresh),runButton));
        panel.add(controls,BorderLayout.NORTH); panel.add(new JScrollPane(result),BorderLayout.CENTER); return panel;
    }
    private void refresh() { invoke("/tasks",null,value -> { tasks.clear(); picker.removeAllItems(); for (var entry : value.getAsJsonArray()) {
        var task = entry.getAsJsonObject(); tasks.add(task); picker.addItem(task.get("kind").getAsString() + " · " + task.get("bean").getAsString() + "." + task.get("method").getAsString());
    } result.setText(tasks.isEmpty() ? "No @Scheduled or @XxlJob handlers found in the running application." : "Select an annotated task. Run invokes it once in the application; application logging stays in the application."); }); }
    private void run() {
        int index = picker.getSelectedIndex(); if (index < 0 || index >= tasks.size()) return;
        var payload = new JsonObject(); payload.add("id",tasks.get(index).get("id")); payload.addProperty("parameter",parameter.getText());
        invoke("/tasks/run",payload,value -> result.setText(new GsonBuilder().setPrettyPrinting().create().toJson(value)));
    }
    private void invoke(String path, JsonObject payload, java.util.function.Consumer<JsonElement> callback) {
        if (busy || isDisposed()) return; busy=true;
        var prefs = BrunoPreferences.bridge(project); String url=prefs.bridgeUrl, reference=prefs.bridgeTokenRef;
        result.setText("Contacting development bridge…"); runButton.setEnabled(false);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                if (isDisposed() || project.isDisposed()) return;
                if (reference.isBlank()) throw new IllegalArgumentException("Configure the development bridge first using the API explorer menu.");
                var active = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(); client=active;
                if (isDisposed()) { active.shutdownNow(); return; }
                try (active) { var value=BridgeClient.call(active,url,SecretVault.read(reference),path,payload); SwingUtilities.invokeLater(() -> { if (!isDisposed()) { callback.accept(value); busy=false; runButton.setEnabled(true); } }); }
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { if (!isDisposed()) { result.setText("Bridge operation failed: " + error.getMessage()); busy=false; runButton.setEnabled(true); } }); }
        });
    }
    @Override public void dispose() { if (client != null) client.shutdownNow(); super.dispose(); }
}
