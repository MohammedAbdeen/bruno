package com.usebruno.intellij.flows;

import com.google.gson.Gson;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

final class EnvironmentDialog extends DialogWrapper {
    private static final Gson JSON = new Gson();
    private final Project project;
    private final Runnable saved;
    private final ApiSettings working;
    private final long openedRevision;
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final JTextField baseUrl = new JTextField();
    private KeyValueEditor variables, headers, defaults;
    private final JPanel editors = new JPanel(new BorderLayout());
    private ApiSettings.Environment active;
    private ApiSettings.Environment mounted;
    private final Set<String> originalSecretRefs;
    private final JLabel notice = new JLabel("Mark sensitive variables Secret to store them in the IDE credential store.");
    private final JPasswordField reveal = new JPasswordField(18);
    private final javax.swing.Timer hide = new javax.swing.Timer(15000, e -> conceal());
    private boolean loading;
    private final Map<String, String> originalNames = new HashMap<>();

    EnvironmentDialog(Project project, Runnable saved) {
        super(project, true);
        this.project = project; this.saved = saved;
        working = JSON.fromJson(JSON.toJson(ApiSettings.get(project)), ApiSettings.class);
        openedRevision = ApiSettings.get(project).profilesRevision();
        originalSecretRefs = secretReferences(working);
        working.environments.forEach(e -> originalNames.put(e.id, e.name));
        setTitle("Bruno Global Environments — shared across projects");
        setOKButtonText("Save Environments");
        init();
        refill(working.selected);
    }

    @Override protected JComponent createCenterPanel() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setPreferredSize(com.intellij.util.ui.JBUI.size(850, 500));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!loading && !e.getValueIsAdjusting()) select(); });
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(BrunoUi.toolbar(
                BrunoUi.icon("New environment", AllIcons.General.Add, () -> create(false)),
                BrunoUi.icon("Duplicate environment", AllIcons.Actions.Copy, () -> create(true)),
                BrunoUi.icon("Rename environment", AllIcons.Actions.Edit, this::rename),
                BrunoUi.icon("Delete environment", AllIcons.General.Remove, this::remove)), BorderLayout.NORTH);
        left.setPreferredSize(com.intellij.util.ui.JBUI.size(190, 400));
        content.add(left, BorderLayout.WEST);
        content.add(editors, BorderLayout.CENTER);
        content.add(notice, BorderLayout.SOUTH);
        reveal.setEditable(false);
        reveal.addFocusListener(new FocusAdapter() { public void focusLost(FocusEvent e) { conceal(); } });
        hide.setRepeats(false);
        return content;
    }

    private void refill(String selected) {
        loading = true;
        names.clear(); working.environments.forEach(e -> names.addElement(e.name));
        list.setSelectedValue(selected, true);
        if (list.getSelectedIndex() < 0 && !names.isEmpty()) list.setSelectedIndex(0);
        loading = false; select();
    }
    private void stash() {
        if (mounted == null || variables == null) return;
        mounted.baseUrl = baseUrl.getText().trim();
        mounted.variableRows = new ArrayList<>(variables.rows());
        mounted.headerRows = new ArrayList<>(headers.rows());
        mounted.structured = true;
        mounted.variables = ""; mounted.headers = "";
        working.defaults = new ArrayList<>(defaults.rows());
    }
    private void select() {
        conceal(); stash();
        String name = list.getSelectedValue();
        ApiSettings.Environment next = working.environments.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null);
        if (next == null) { active = null; mounted = null; return; }
        active = next;
        try { active.migrate(); } catch (IllegalArgumentException error) {
            mounted = null;
            notice.setText("Legacy environment needs repair: " + error.getMessage());
            editors.removeAll();
            JLabel errLabel = new JLabel("<html><b>Cannot edit legacy environment:</b> " + BrunoUi.html(error.getMessage())
                    + "<br><br>The legacy variables/headers text contains errors and cannot be parsed.<br>Fix the raw format or delete this environment.</html>");
            errLabel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            editors.add(errLabel, BorderLayout.CENTER);
            editors.revalidate(); editors.repaint();
            return;
        }
        mounted = active;
        baseUrl.setToolTipText("Defines {{baseUrl}} for requests in this environment, e.g. http://localhost:8080");
        baseUrl.setText(active.baseUrl);
        variables = new KeyValueEditor(active.variableRows, true, true, () -> {});
        headers = new KeyValueEditor(active.headerRows, false, false, () -> {});
        defaults = new KeyValueEditor(working.defaults, true, true, () -> {});
        JTabbedPane tabs = new BrunoTabbedPane();
        JPanel vars = new JPanel(new BorderLayout());
        vars.add(variables, BorderLayout.CENTER);
        vars.add(BrunoUi.toolbar(
                BrunoUi.button("Show selected secret", AllIcons.Actions.Preview, () -> showSecret(false)),
                BrunoUi.button("Copy secret", AllIcons.Actions.Copy, () -> showSecret(true)), reveal), BorderLayout.SOUTH);
        tabs.addTab("Variables", vars);
        tabs.addTab("Default headers", headers);
        tabs.addTab("Global variables", defaults);
        editors.removeAll();
        editors.add(BrunoUi.labeled("Base URL", baseUrl), BorderLayout.NORTH);
        editors.add(tabs, BorderLayout.CENTER);
        editors.revalidate(); editors.repaint();
    }
    private void create(boolean duplicate) {
        stash();
        String name = JOptionPane.showInputDialog(getContentPanel(), "Environment name");
        if (name == null || name.isBlank()) return;
        name = name.trim();
        if (names.contains(name)) { notice.setText("That name already exists."); return; }
        ApiSettings.Environment env = duplicate && active != null
                ? JSON.fromJson(JSON.toJson(active), ApiSettings.Environment.class) : new ApiSettings.Environment();
        env.name = name; env.id = UUID.randomUUID().toString();
        // Existing secret references can be reused until that environment's value is changed.
        working.environments.add(env); refill(name);
    }
    private void rename() {
        if (active == null) return;
        String name = JOptionPane.showInputDialog(getContentPanel(), "New environment name", active.name);
        if (name == null || name.isBlank() || names.contains(name.trim())) return;
        stash(); active.name = name.trim(); refill(active.name);
    }
    private void remove() {
        if (active == null || working.environments.size() == 1) return;
        if (JOptionPane.showConfirmDialog(getContentPanel(), "Delete " + active.name + "? Request drafts will be preserved.",
                "Delete environment", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        working.environments.remove(active); active = null; mounted = null;
        refill(working.environments.getFirst().name);
    }
    private void showSecret(boolean copy) {
        int index = variables.table.getSelectedRow();
        if (index < 0) { notice.setText("Select a secret variable first."); return; }
        RequestRow row = variables.rows().get(index);
        if (!row.secret) { notice.setText("The selected variable is not marked secret."); return; }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                String value = row.secretEdited || row.secretRef.isEmpty() ? row.value : SecretVault.read(row.secretRef);
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    if (copy) BrunoUi.copy(value, notice, "Secret copied");
                    else { reveal.setText(value); reveal.setEchoChar((char) 0); reveal.requestFocusInWindow(); hide.restart(); }
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { notice.setText("Secret unavailable. Enter a replacement value."); BrunoNotifications.error(project, "Secret unavailable. Enter a replacement value in Environments."); }); }
        });
    }
    private void conceal() { reveal.setText(""); reveal.setEchoChar('•'); hide.stop(); }

    @Override protected void doOKAction() {
        if (ApiSettings.get(project).profilesRevision() != openedRevision) {
            setErrorText("Global environments changed in another window. Reopen this manager before saving; copy any unsaved edits first."); return;
        }
        stash();
        try {
            for (var env : working.environments) { env.migrate(); validate(env.variableRows); }
            validate(working.defaults);
        } catch (IllegalArgumentException error) { setErrorText(error.getMessage()); return; }
        setOKActionEnabled(false); getCancelAction().setEnabled(false);
        notice.setText("Saving credentials…");
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                saveSecrets(working.defaults, "project");
                for (var env : working.environments) saveSecrets(env.variableRows, env.id);
                SwingUtilities.invokeLater(() -> {
                    if (ApiSettings.get(project).profilesRevision() != openedRevision) {
                        setOKActionEnabled(true); getCancelAction().setEnabled(true);
                        setErrorText("Global environments changed during the save. Reopen the manager before saving."); return;
                    }
                    working.selected = active != null ? active.name : (!working.environments.isEmpty() ? working.environments.getFirst().name : "");
                    // Retain a retry queue if the credential provider is locked.
                    var obsolete = new HashSet<>(originalSecretRefs);
                    obsolete.addAll(working.pendingSecretDeletions);
                    obsolete.removeAll(secretReferences(working));
                    working.pendingSecretDeletions = new ArrayList<>(obsolete);
                    var sessions = ApiSettings.get(project).sessions;
                    XmlSerializerUtil.copyBean(working, ApiSettings.get(project));
                    ApiSettings.get(project).sessions = sessions;
                    ApiSettings.get(project).changed();
                    cleanupCredentials(obsolete);
                    for (var env : working.environments) {
                        String old = originalNames.get(env.id);
                        if (old == null || old.equals(env.name)) continue;
                        for (var draft : project.getService(WorkspaceState.class).drafts) {
                            if (draft.fields.environment.equals(old)) draft.fields.environment = env.name;
                            if (!draft.baseline.isEmpty()) {
                                var baseline = JSON.fromJson(draft.baseline, RequestDraft.Fields.class);
                                if (baseline.environment.equals(old)) {
                                    baseline.environment = env.name;
                                    var saved = new RequestDraft(); saved.fields = baseline; draft.baseline = saved.snapshot();
                                }
                            }
                        }
                    }
                    saved.run(); close(OK_EXIT_CODE);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    setOKActionEnabled(true); getCancelAction().setEnabled(true);
                    notice.setText("Credential storage failed. Project settings were not changed.");
                    BrunoNotifications.error(project, "Credential storage failed (" + error.getClass().getSimpleName()
                            + "). Check that your IDE credential provider is unlocked, then retry. Project settings were not changed.");
                });
            }
        });
    }
    private void cleanupCredentials(Set<String> obsolete) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            Set<String> removed = new HashSet<>();
            for (String ref : obsolete) {
                try { SecretVault.delete(ref); removed.add(ref); }
                catch (Exception ignored) { }
            }
            SwingUtilities.invokeLater(() -> {
                if (project.isDisposed()) return;
                ApiSettings.get(project).pendingSecretDeletions.removeAll(removed);
                if (removed.size() < obsolete.size()) BrunoNotifications.error(project,
                        "Some removed credentials could not be deleted. Unlock the IDE credential provider; cleanup will retry on the next environment save.");
            });
        });
    }
    static Set<String> secretReferences(ApiSettings settings) {
        Set<String> refs = new HashSet<>();
        java.util.stream.Stream.concat(settings.defaults.stream(), settings.environments.stream().flatMap(e -> e.variableRows.stream()))
                .filter(r -> r.secret && !r.secretRef.isBlank()).forEach(r -> refs.add(r.secretRef));
        return refs;
    }
    private static void validate(List<RequestRow> rows) {
        Set<String> keys = new HashSet<>();
        for (var row : rows) if (row.enabled && !row.key.isBlank()) {
            if (!keys.add(row.key)) throw new IllegalArgumentException("Duplicate variable: " + row.key);
            if (row.key.equals("baseUrl")) throw new IllegalArgumentException("baseUrl is reserved; use the Base URL field.");
        }
    }
    private void saveSecrets(List<RequestRow> rows, String scope) {
        for (var row : rows) if (row.secret) {
            if (row.secretEdited || row.secretRef.isEmpty()) {
                // New immutable reference makes partially failed saves harmless to the existing environment.
                row.secretRef = SecretVault.createReference(null, working.projectId, scope);
                SecretVault.write(row.secretRef, row.value);
            }
            row.value = ""; row.secretEdited = false;
        }
    }
    @Override public void dispose() { conceal(); super.dispose(); }
}
