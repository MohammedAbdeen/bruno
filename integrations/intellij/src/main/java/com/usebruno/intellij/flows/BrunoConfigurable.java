package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.*;
import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;

public final class BrunoConfigurable implements Configurable {
    private final Project project;
    private final JTextField node = new JTextField();
    private final JTextField flowCli = new JTextField();
    private final JTextField runtime = new JTextField();
    private final JComboBox<String> scope = new JComboBox<>(new String[]{"This Project", "IDE Defaults"});
    private final JSpinner connect = new JSpinner(new SpinnerNumberModel(15, 1, 120, 1));
    private final JSpinner total = new JSpinner(new SpinnerNumberModel(30, 1, 600, 1));
    private final JSpinner hops = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
    private final JSpinner preview = new JSpinner(new SpinnerNumberModel(1024, 64, 10240, 64));
    private final JCheckBox redirects = new JCheckBox("Follow same-origin redirects");
    private final JCheckBox restore = new JCheckBox("Restore open request tabs");
    private final JCheckBox inheritConnect = new JCheckBox("Use IDE default");
    private final JCheckBox inheritTotal = new JCheckBox("Use IDE default");
    private final JCheckBox inheritRedirects = new JCheckBox("Use IDE default");
    private final JCheckBox inheritHops = new JCheckBox("Use IDE default");
    private final JCheckBox inheritPreview = new JCheckBox("Use IDE default");
    private final JCheckBox inheritRestore = new JCheckBox("Use IDE default");
    private JPanel panel;
    private int previousScope;
    private boolean changingScope;

    public BrunoConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Bruno"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(BrunoUi.labeled("Settings scope", scope));
        panel.add(row("Connect timeout (seconds)", connect, inheritConnect));
        panel.add(row("Total deadline (seconds)", total, inheritTotal));
        panel.add(row("Redirect behavior", redirects, inheritRedirects));
        panel.add(row("Maximum redirect hops", hops, inheritHops));
        panel.add(row("Response preview (KiB)", preview, inheritPreview));
        panel.add(row("Workspace", restore, inheritRestore));
        for (var field : new JComponent[]{BrunoUi.labeled("Node executable (IDE-wide)",node), BrunoUi.labeled("Bruno JS package directory (IDE-wide)",runtime)}) {
            field.setMaximumSize(com.intellij.util.ui.JBUI.size(Integer.MAX_VALUE,36)); panel.add(field);
        }
        var cliInput=new JPanel(new BorderLayout(4,0)); cliInput.add(flowCli,BorderLayout.CENTER);
        cliInput.add(BrunoUi.button("Browse…",null,() -> {
            var chooser=new JFileChooser(); chooser.setDialogTitle("Select Bruno CLI file or checkout"); chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(panel)==JFileChooser.APPROVE_OPTION) flowCli.setText(chooser.getSelectedFile().getAbsolutePath());
        }),BorderLayout.EAST);
        var cliRow=BrunoUi.labeled("Flow CLI path or checkout (IDE-wide)",cliInput);
        cliRow.setMaximumSize(com.intellij.util.ui.JBUI.size(Integer.MAX_VALUE,36)); panel.add(cliRow);
        flowCli.setToolTipText("Path to this fork's packages/bruno-cli/bin/bru.js, a Bruno checkout, or a flow-enabled CLI executable. Blank uses automatic discovery/PATH.");
        panel.add(new JLabel("Flows require this fork's CLI with --flow support; external Java projects can point to your Bruno checkout."));
        panel.add(new JLabel("Scripts reuse @usebruno/js QuickJS; runtime directory is optional when installed in the project."));
        panel.add(new JLabel("The total deadline includes connection, redirects, and reading the body."));
        panel.add(new JLabel("Cross-origin redirects and HTTPS downgrades require manual review."));
        JButton reset = new JButton("Reset this section to defaults");
        reset.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(panel, "Reset request settings in this scope? Drafts and environments are preserved.",
                    "Reset Bruno settings", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                connect.setValue(15); total.setValue(30); hops.setValue(5); preview.setValue(1024);
                redirects.setSelected(false); restore.setSelected(true);
                if (scope.getSelectedIndex() == 0) for (var check : inherited()) check.setSelected(true);
                enableFields();
            }
        });
        panel.add(reset);
        scope.addActionListener(e -> changeScope());
        for (var check : inherited()) check.addActionListener(e -> enableFields());
        reset(); return panel;
    }
    private JPanel row(String name, JComponent value, JCheckBox inherit) {
        JPanel row = new JPanel(new BorderLayout(8, 6));
        row.add(BrunoUi.labeled(name, value), BorderLayout.CENTER); row.add(inherit, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, com.intellij.util.ui.JBUI.scale(36))); return row;
    }
    private JCheckBox[] inherited() { return new JCheckBox[]{inheritConnect, inheritTotal, inheritRedirects, inheritHops, inheritPreview, inheritRestore}; }
    private void changeScope() {
        if (changingScope) return;
        changingScope = true;
        int next = scope.getSelectedIndex();
        scope.setSelectedIndex(previousScope);
        if (isModified()) {
            int answer = JOptionPane.showOptionDialog(panel, "Apply changes before switching settings scope?", "Bruno Settings",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new String[]{"Apply", "Discard", "Cancel"}, "Apply");
            if (answer == 0) {
                try { apply(); } catch (ConfigurationException error) {
                    BrunoNotifications.error(project, error.getMessage()); next = previousScope;
                }
            } else if (answer != 1) next = previousScope;
        }
        boolean switched = previousScope != next;
        previousScope = next; scope.setSelectedIndex(next);
        if (switched) reset();
        changingScope = false;
    }
    private void enableFields() {
        if (scope.getSelectedIndex() == 0) {
            var defaults = ApplicationManager.getApplication().getService(BrunoPreferences.class);
            if (inheritConnect.isSelected()) connect.setValue(defaults.connectSeconds);
            if (inheritTotal.isSelected()) total.setValue(defaults.totalSeconds);
            if (inheritHops.isSelected()) hops.setValue(defaults.redirectLimit);
            if (inheritPreview.isSelected()) preview.setValue(defaults.previewKiB);
            if (inheritRedirects.isSelected()) redirects.setSelected(defaults.redirects);
            if (inheritRestore.isSelected()) restore.setSelected(defaults.restoreTabs);
        }
        JComponent[] controls = {connect, total, redirects, hops, preview, restore};
        var checks = inherited();
        for (int i = 0; i < controls.length; i++) {
            checks[i].setVisible(scope.getSelectedIndex() == 0);
            controls[i].setEnabled(scope.getSelectedIndex() == 1 || !checks[i].isSelected());
        }
    }
    @Override public void reset() {
        var defaults = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        var local = project.getService(ProjectPreferences.class);
        node.setText(defaults.nodeExecutable); runtime.setText(defaults.jsRuntimePath); flowCli.setText(defaults.flowCliPath);
        boolean global = scope.getSelectedIndex() == 1;
        connect.setValue(global || local.connectSeconds == null ? defaults.connectSeconds : local.connectSeconds);
        total.setValue(global || local.totalSeconds == null ? defaults.totalSeconds : local.totalSeconds);
        hops.setValue(global || local.redirectLimit == null ? defaults.redirectLimit : local.redirectLimit);
        preview.setValue(global || local.previewKiB == null ? defaults.previewKiB : local.previewKiB);
        redirects.setSelected(global || local.redirects == null ? defaults.redirects : local.redirects);
        restore.setSelected(global || local.restoreTabs == null ? defaults.restoreTabs : local.restoreTabs);
        inheritConnect.setSelected(local.connectSeconds == null); inheritTotal.setSelected(local.totalSeconds == null);
        inheritHops.setSelected(local.redirectLimit == null); inheritPreview.setSelected(local.previewKiB == null);
        inheritRedirects.setSelected(local.redirects == null); inheritRestore.setSelected(local.restoreTabs == null);
        enableFields();
    }
    @Override public boolean isModified() {
        if (panel == null) return false;
        var defaults = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        var local = project.getService(ProjectPreferences.class);
        if (!defaults.nodeExecutable.equals(node.getText()) || !defaults.jsRuntimePath.equals(runtime.getText()) || !defaults.flowCliPath.equals(flowCli.getText())) return true;
        if (scope.getSelectedIndex() == 1) return defaults.connectSeconds != number(connect) || defaults.totalSeconds != number(total)
                || defaults.redirectLimit != number(hops) || defaults.previewKiB != number(preview)
                || defaults.redirects != redirects.isSelected() || defaults.restoreTabs != restore.isSelected();
        return !java.util.Objects.equals(local.connectSeconds, inheritConnect.isSelected() ? null : number(connect))
                || !java.util.Objects.equals(local.totalSeconds, inheritTotal.isSelected() ? null : number(total))
                || !java.util.Objects.equals(local.redirectLimit, inheritHops.isSelected() ? null : number(hops))
                || !java.util.Objects.equals(local.previewKiB, inheritPreview.isSelected() ? null : number(preview))
                || !java.util.Objects.equals(local.redirects, inheritRedirects.isSelected() ? null : redirects.isSelected())
                || !java.util.Objects.equals(local.restoreTabs, inheritRestore.isSelected() ? null : restore.isSelected());
    }
    @Override public void apply() throws ConfigurationException {
        try {
            for (var spinner : new JSpinner[]{connect, total, hops, preview}) spinner.commitEdit();
            new TransportOptions(number(connect), number(total), redirects.isSelected(), number(hops), number(preview) * 1024).validate();
        } catch (Exception error) { throw new ConfigurationException("Enter valid timeout, redirect, and preview limits."); }
        var scripting = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        scripting.nodeExecutable = node.getText().isBlank() ? "node" : node.getText().strip(); scripting.jsRuntimePath = runtime.getText().strip(); scripting.flowCliPath=flowCli.getText().strip();
        if (scope.getSelectedIndex() == 1) {
            var defaults = ApplicationManager.getApplication().getService(BrunoPreferences.class);
            defaults.connectSeconds = number(connect); defaults.totalSeconds = number(total);
            defaults.redirectLimit = number(hops); defaults.previewKiB = number(preview);
            defaults.redirects = redirects.isSelected(); defaults.restoreTabs = restore.isSelected();
        } else {
            var local = project.getService(ProjectPreferences.class);
            local.connectSeconds = inheritConnect.isSelected() ? null : number(connect);
            local.totalSeconds = inheritTotal.isSelected() ? null : number(total);
            local.redirectLimit = inheritHops.isSelected() ? null : number(hops);
            local.previewKiB = inheritPreview.isSelected() ? null : number(preview);
            local.redirects = inheritRedirects.isSelected() ? null : redirects.isSelected();
            local.restoreTabs = inheritRestore.isSelected() ? null : restore.isSelected();
        }
    }
    private static int number(JSpinner spinner) { return ((Number) spinner.getValue()).intValue(); }
}
