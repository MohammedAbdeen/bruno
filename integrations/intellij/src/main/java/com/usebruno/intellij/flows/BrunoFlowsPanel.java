package com.usebruno.intellij.flows;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

final class BrunoFlowsPanel extends JPanel implements com.intellij.openapi.Disposable {
    private final Project project;
    private final JComboBox<FlowFile> flowPicker = new JComboBox<>();
    private final JTextField environmentField = new JTextField(12);
    private final JButton refreshButton = BrunoUi.icon("Refresh Flows", AllIcons.Actions.Refresh, this::refreshFlows);
    private final JButton runButton = BrunoUi.primaryButton("Run Flow", AllIcons.Actions.Execute, this::runSelectedFlow);
    private final JButton stopButton = BrunoUi.button("Stop", AllIcons.Actions.Suspend, this::stopFlow);
    private final JTextArea output = new JTextArea();
    private final AtomicReference<Process> runningProcess = new AtomicReference<>();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final FlowRunHistory history;
    private final FlowRunView runView;
    private final JTabbedPane executionTabs=new BrunoTabbedPane();
    private volatile boolean disposed,stopRequested;
    private java.util.concurrent.Future<?> importTask;
    private final java.util.concurrent.atomic.AtomicBoolean executing=new java.util.concurrent.atomic.AtomicBoolean();
    private final StringBuilder pendingOutput=new StringBuilder();
    private boolean outputScheduled;
    private final java.util.List<JButton> importButtons=new java.util.ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean importing=new java.util.concurrent.atomic.AtomicBoolean();
    private Path selectAfterRefresh;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);

    BrunoFlowsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        history=project.getService(FlowRunHistory.class); runView=new FlowRunView(history);
        com.intellij.openapi.util.Disposer.register(this,runView);
        setBorder(JBUI.Borders.empty(6));
        output.setEditable(false);
        output.setLineWrap(true); output.setWrapStyleWord(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        output.setBackground(JBColor.namedColor("Editor.background", new Color(0x2B2D30)));
        output.setForeground(JBColor.namedColor("Editor.foreground", new Color(0xDFE1E5)));
        output.setBorder(JBUI.Borders.empty(6));

        stopButton.setEnabled(false);
        flowPicker.setPrototypeDisplayValue(new FlowFile(Path.of("collections/example/flows/example.flow.bru"), Path.of(".")));

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));

        flowPicker.getAccessibleContext().setAccessibleName("Flow");
        environmentField.getAccessibleContext().setAccessibleName("Collection environment");
        environmentField.setToolTipText("Bruno collection environment name. Define flow variables in that collection; API Environments are separate.");
        environmentField.putClientProperty("JTextField.placeholderText", "Environment");
        JPanel row1=new ResponsiveRow(BrunoUi.labeled("Flow",flowPicker),BrunoUi.labeled("Collection env",environmentField));

        JPanel row2 = new WrappingToolbar(FlowLayout.LEADING);
        row2.add(runButton);
        row2.add(stopButton);
        row2.add(BrunoUi.icon("Create new flow", AllIcons.General.Add, this::createNewFlow));
        row2.add(BrunoUi.icon("Open flow in editor", AllIcons.Actions.Edit, this::openFlowInEditor));
        row2.add(refreshButton);
        row2.add(BrunoUi.button("Delete Flow", AllIcons.Actions.GC, this::deleteSelectedFlow));
        row2.add(BrunoUi.button("Clear Output", null, () -> output.setText("")));

        toolbar.add(row1);
        toolbar.add(Box.createVerticalStrut(JBUI.scale(4)));
        toolbar.add(row2);
        JPanel runnerSetup=new WrappingToolbar(FlowLayout.LEADING,importButton(),
                BrunoUi.button("Runner settings…",AllIcons.General.Settings,() ->
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().editConfigurable(project,new BrunoConfigurable(project))));
        toolbar.add(runnerSetup);

        JPanel runnerCard = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
        runnerCard.add(toolbar, BorderLayout.NORTH);
        executionTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        executionTabs.addTab("Execution history",runView); executionTabs.addTab("Console",new JScrollPane(output));
        runnerCard.add(executionTabs, BorderLayout.CENTER);

        JPanel emptyCard = new JPanel(new GridBagLayout());
        JPanel emptyInner = new JPanel() {
            @Override public Dimension getPreferredSize() {
                var size=super.getPreferredSize();
                if (emptyCard.getWidth()>0) size.width=Math.min(size.width,Math.max(1,emptyCard.getWidth()-JBUI.scale(16)));
                return size;
            }
        };
        emptyInner.setLayout(new BoxLayout(emptyInner, BoxLayout.Y_AXIS));

        JLabel emptyIcon = new JLabel(AllIcons.Nodes.Services);
        emptyIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel emptyTitle = new JLabel("Bruno API Flows");
        emptyTitle.setFont(emptyTitle.getFont().deriveFont(Font.BOLD, JBUI.scale(15f)));
        emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel emptySubtitle = new JLabel("<html><center>Chain API requests with<br>assertions, loops and scripts.</center></html>");
        emptySubtitle.setForeground(BrunoUi.MUTED);
        emptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel emptyActions = new JPanel(); emptyActions.setLayout(new BoxLayout(emptyActions,BoxLayout.Y_AXIS));
        JPanel createActions=new WrappingToolbar(FlowLayout.CENTER);
        JPanel setupActions=new WrappingToolbar(FlowLayout.CENTER);
        createActions.add(BrunoUi.primaryButton("Create New Flow", AllIcons.General.Add, this::createNewFlow));
        createActions.add(importButton());
        setupActions.add(BrunoUi.button("Init Bruno Collection", AllIcons.Nodes.Folder, this::initCollection));
        setupActions.add(BrunoUi.button("Refresh", AllIcons.Actions.Refresh, this::refreshFlows));
        emptyActions.add(createActions); emptyActions.add(setupActions);
        emptyActions.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel tipLabel = new JLabel("<html><center>Import JSON or create a flow<br>inside a Bruno collection.</center></html>");
        tipLabel.setFont(JBUI.Fonts.smallFont());
        tipLabel.setForeground(BrunoUi.MUTED);
        tipLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyInner.add(emptyIcon);
        emptyInner.add(Box.createVerticalStrut(JBUI.scale(8)));
        emptyInner.add(emptyTitle);
        emptyInner.add(Box.createVerticalStrut(JBUI.scale(4)));
        emptyInner.add(emptySubtitle);
        emptyInner.add(Box.createVerticalStrut(JBUI.scale(12)));
        emptyInner.add(emptyActions);
        emptyInner.add(Box.createVerticalStrut(JBUI.scale(10)));
        emptyInner.add(tipLabel);
        emptyCard.add(emptyInner);

        cardHost.add(emptyCard, "empty");
        cardHost.add(runnerCard, "runner");
        add(cardHost, BorderLayout.CENTER);

        refreshFlows();
    }

    private JButton importButton() {
        var button=BrunoUi.button("Import JSON…",AllIcons.Actions.MenuOpen,this::chooseFlowJson);
        button.setToolTipText("Import a Bruno flow JSON file into a collection"); importButtons.add(button); return button;
    }
    private void chooseFlowJson() {
        if (executing.get()) { BrunoNotifications.info(project,"Stop the running flow before importing into a collection."); return; }
        if (importing.get()) return;
        var json=com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("json");
        json.setTitle("Import Flow JSON"); json.setDescription("Choose an exported Bruno flow or graph JSON file (up to 10 MiB).");
        var source=com.intellij.openapi.fileChooser.FileChooser.chooseFile(json,project,null); if (source==null) return;
        var folder=com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor();
        folder.setTitle("Choose Destination Bruno Collection"); folder.setDescription("The imported flow is saved under flows/. Existing files are preserved; a new folder gets collection metadata.");
        var selected=(FlowFile)flowPicker.getSelectedItem();
        String base=project.getBasePath();
        var initial=selected!=null ? com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(selected.collectionRoot())
                : base==null ? null : com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(Path.of(base));
        var destination=com.intellij.openapi.fileChooser.FileChooser.chooseFile(folder,project,initial); if (destination!=null) importFlowJson(source,destination);
    }
    void importFlowJson(com.intellij.openapi.vfs.VirtualFile source,com.intellij.openapi.vfs.VirtualFile destination) {
        if (disposed || project.isDisposed() || executing.get() || !importing.compareAndSet(false,true)) return;
        runButton.setEnabled(false);
        importButtons.forEach(button -> { button.setEnabled(false); button.setText("Importing…"); });
        try { importTask=ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var root=FlowFileOperations.collectionRoot(destination); boolean yaml=FlowFileOperations.yaml(root);
                var prepared=FlowJsonImport.read(Path.of(source.getPath()),Path.of(root.getPath()),yaml);
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        if (disposed || project.isDisposed()) return;
                        var file=FlowFileOperations.importJson(project,root,prepared);
                        rememberCollection(root.getPath()); selectAfterRefresh=Path.of(file.getPath()); refreshFlows();
                        FileEditorManager.getInstance(project).openFile(file,true);
                        append("Imported flow: "+file.getPath()+"\n");
                        BrunoNotifications.info(project,"Imported "+prepared.name()+". Select Run Flow when ready.");
                    } catch (Exception error) { BrunoNotifications.error(project,"Could not import flow JSON: "+BrunoUi.html(java.util.Objects.toString(error.getMessage(),"Unknown import error"))); }
                    finally { finishImport(); }
                },com.intellij.openapi.application.ModalityState.nonModal());
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) BrunoNotifications.error(project,"Could not import flow JSON: "+BrunoUi.html(java.util.Objects.toString(error.getMessage(),"Unknown import error")));
                    finishImport();
                });
            }
        }); } catch (RuntimeException error) {
            finishImport(); BrunoNotifications.error(project,"Could not start flow import: "+BrunoUi.html(java.util.Objects.toString(error.getMessage(),"Import unavailable")));
        }
    }
    private void finishImport() {
        importing.set(false);
        if (!disposed) {
            importButtons.forEach(button -> { button.setEnabled(true); button.setText("Import JSON…"); });
            runButton.setEnabled(!executing.get() && flowPicker.getSelectedItem()!=null);
        }
    }

    private void createNewFlow() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            BrunoNotifications.error(project, "Please open a project first.");
            return;
        }
        String name = Messages.showInputDialog(project, "Flow name (e.g. smoke-test):", "New Bruno Flow", AllIcons.General.Add);
        if (name == null || name.isBlank()) return;
        try {
            var selected = (FlowFile) flowPicker.getSelectedItem();
            var descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor();
            descriptor.setTitle("Choose Bruno Collection Folder");
            descriptor.setDescription("The flow will be created under flows/. Choose a writable folder; a collection marker is created if needed.");
            var root = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project,
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(selected == null ? Path.of(basePath) : selected.collectionRoot()));
            if (root == null) return;
            var file = FlowFileOperations.create(project, root, name.trim());
            rememberCollection(root.getPath());
            refreshFlows(); FileEditorManager.getInstance(project).openFile(file, true);
            append("Created new flow: " + file.getPath() + "\n");
        } catch (Exception ex) {
            BrunoNotifications.error(project, "Could not create flow: " + ex.getMessage());
        }
    }
    private void deleteSelectedFlow() {
        var flow = (FlowFile) flowPicker.getSelectedItem();
        if (flow == null) return;
        if (executing.get()) {
            BrunoNotifications.info(project, "Stop the running flow before deleting a flow file."); return;
        }
        if (Messages.showYesNoDialog(project, "Delete " + flow.path() + "?", "Delete Flow", Messages.getWarningIcon()) != Messages.YES) return;
        try {
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(flow.path());
            if (file == null) throw new java.io.IOException("Flow file no longer exists. Refresh the list.");
            FlowFileOperations.delete(project, file); refreshFlows();
            append("Deleted flow: " + flow.path().getFileName() + "\n");
        } catch (Exception error) { BrunoNotifications.error(project, error.getMessage()); }
    }

    private void openFlowInEditor() {
        FlowFile flow = (FlowFile) flowPicker.getSelectedItem();
        if (flow == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var vf = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(flow.path());
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (vf != null && !project.isDisposed()) FileEditorManager.getInstance(project).openFile(vf, true);
            });
        });
    }

    private void initCollection() {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        var descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Choose Bruno Collection Folder");
        var root = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project,
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath)));
        if (root == null) return;
        if (root.findChild("bruno.json") != null || root.findChild("opencollection.yml") != null || root.findChild("opencollection.yaml") != null) {
            BrunoNotifications.info(project, "A Bruno collection already exists in this folder."); return;
        }
        try {
            com.intellij.openapi.command.WriteCommandAction.writeCommandAction(project).withName("Initialize Bruno collection").run(() -> {
                var file = root.createChildData(this, "bruno.json");
                com.intellij.openapi.vfs.VfsUtil.saveText(file, new com.google.gson.Gson().toJson(java.util.Map.of("version", "1", "name", root.getName(), "type", "collection")));
            });
            rememberCollection(root.getPath()); refreshFlows();
        } catch (Exception error) {
            BrunoNotifications.error(project, "Could not initialize collection. Choose a writable folder. " + error.getMessage());
        }
    }

    private void rememberCollection(String path) {
        var collections = project.getService(WorkspaceState.class).flowCollections;
        if (!collections.contains(path)) collections.add(path);
    }
    private void refreshFlows() {
        Object previous = flowPicker.getSelectedItem();
        int generation = scanGeneration.incrementAndGet();
        refreshButton.setEnabled(false);
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                refreshButton.setEnabled(true);
                cards.show(cardHost, history.runs().isEmpty() ? "empty" : "runner");
                return;
            }
            List<String> extraCollections = List.copyOf(project.getService(WorkspaceState.class).flowCollections);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    var found = new java.util.LinkedHashMap<Path, FlowFile>();
                    for (var flow : FlowLocator.findFlows(Path.of(basePath))) found.put(flow.path(), flow);
                    for (String path : extraCollections) {
                        try { for (var flow : FlowLocator.findFlows(Path.of(path))) found.put(flow.path(), flow); }
                        catch (java.io.IOException error) { append("Could not scan collection " + path + ": " + error.getMessage() + "\n"); }
                    }
                    List<FlowFile> flows = List.copyOf(found.values());
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (generation != scanGeneration.get()) return;
                        flowPicker.removeAllItems();
                        for (FlowFile flow : flows) flowPicker.addItem(flow);
                        Path desired=selectAfterRefresh!=null ? selectAfterRefresh : previous instanceof FlowFile value ? value.path() : null;
                        if (desired!=null) {
                            for (int i = 0; i < flowPicker.getItemCount(); i++) {
                                if (flowPicker.getItemAt(i).path().equals(desired)) {
                                    selectAfterRefresh=null;
                                    flowPicker.setSelectedIndex(i);
                                    break;
                                }
                            }
                        }
                        if (flows.isEmpty()) {
                            cards.show(cardHost, history.runs().isEmpty() ? "empty" : "runner");
                        } else {
                            cards.show(cardHost, "runner");
                        }
                        runButton.setEnabled(!executing.get() && !importing.get() && flowPicker.getSelectedItem()!=null);
                        refreshButton.setEnabled(true);
                    });
                } catch (Exception exception) {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (generation != scanGeneration.get()) return;
                        append("Could not scan project for flows: " + exception.getMessage() + "\n");
                        runButton.setEnabled(!executing.get() && !importing.get() && flowPicker.getSelectedItem()!=null);
                        refreshButton.setEnabled(true);
                        cards.show(cardHost, history.runs().isEmpty() ? "empty" : "runner");
                    });
                }
            });
        } catch (Exception exception) {
            refreshButton.setEnabled(true);
            append("Could not scan project for flows: " + exception.getMessage() + "\n");
            cards.show(cardHost, history.runs().isEmpty() ? "empty" : "runner");
        }
    }

    private void runSelectedFlow() {
        FlowFile flow = (FlowFile) flowPicker.getSelectedItem();
        if (flow == null || disposed || importing.get() || !executing.compareAndSet(false,true)) return;
        stopRequested=false;
        String environment=environmentField.getText();
        output.setText("Resolving flow runner…\n");
        executionTabs.setSelectedIndex(1);
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        Thread runner = new Thread(() -> execute(flow,environment), "bruno-flow-runner");
        runner.setDaemon(true);
        runner.start();
    }

    private void execute(FlowFile flow,String environment) {
        Process process=null; Path reportFile=null;
        var started=java.time.Instant.now(); long start=System.nanoTime();
        int exitCode=1; String report="{}"; boolean completed=false;
        var console=new StringBuilder();
        java.util.function.Consumer<String> publish=text -> {
            console.append(text); if (console.length()>512*1024) console.delete(0,console.length()-256*1024);
            append(text);
        };
        try {
            if (disposed || project.isDisposed() || stopRequested) return;
            reportFile=Files.createTempFile("bruno-flow-run-",".json");
            List<String> command=new java.util.ArrayList<>(BrunoCli.command(project,flow,environment));
            command.addAll(List.of("--reporter-json",reportFile.toString()));
            if (disposed || project.isDisposed() || stopRequested) return;
            publish.accept("$ "+String.join(" ",command)+"\n\n");
            process=new ProcessBuilder(command).directory(flow.collectionRoot().toFile()).redirectErrorStream(true).start();
            runningProcess.set(process);
            if (disposed || project.isDisposed() || stopRequested) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); return; }
            javax.swing.SwingUtilities.invokeLater(() -> { if (!disposed && !stopRequested) stopButton.setEnabled(true); });
            process.getOutputStream().close();
            try (var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                char[] buffer=new char[4096]; int count; while ((count=reader.read(buffer))!=-1) publish.accept(new String(buffer,0,count));
            }
            exitCode=process.waitFor(); completed=true;
            publish.accept("\nBruno finished with exit code "+exitCode+".\n");
            report=FlowRunHistory.readReport(reportFile);
            if (report.equals("{}")) publish.accept("No step report was produced. Check the CLI error above and use this fork's flow-enabled CLI.\n");
        } catch (Exception exception) {
            if (stopRequested) publish.accept("Flow stopped.\n");
            else publish.accept(completed ? "Could not load execution details: "+exception.getMessage()+"\n" : "Could not run Bruno CLI: "+exception.getMessage()+"\nOpen Runner settings… and configure this fork's CLI and Node executable.\n");
        } finally {
            if (reportFile!=null) try { Files.deleteIfExists(reportFile); } catch (java.io.IOException error) { reportFile.toFile().deleteOnExit(); publish.accept("Could not remove temporary report: "+reportFile+"\n"); }
            if (process!=null && process.isAlive()) process.destroyForcibly();
            runningProcess.compareAndSet(process,null);
            if (stopRequested && report.equals("{}")) report="{\"status\":\"cancelled\",\"error\":\"Stopped before a complete step report was available\"}";
            if (!disposed && !project.isDisposed()) {
                FlowRunHistory.Run saved=null;
                try {
                    saved=history.add(started,flow.path().toString(),environment,exitCode,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),report,
                            console.length()>256*1024 ? console.substring(console.length()-256*1024) : console.toString());
                } catch (RuntimeException error) { publish.accept("Could not retain run history: "+error.getMessage()+"\n"); }
                final var record=saved;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        if (disposed || project.isDisposed()) return;
                        runView.refresh(record); executionTabs.setSelectedIndex(0);
                    } finally {
                        executing.set(false);
                        if (!disposed) {
                            runButton.setEnabled(!importing.get() && flowPicker.getSelectedItem()!=null);
                            stopButton.setEnabled(false);
                        }
                    }
                });
            } else executing.set(false);
        }
    }

    private void stopFlow() {
        if (!executing.get()) return;
        stopRequested=true;
        Process process = runningProcess.getAndSet(null);
        append("\nStopping flow...\n");
        stopButton.setEnabled(false);
        if (process==null) return;
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroy);
                process.destroy();
                boolean done = process.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!done) {
                    descendants.forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
        });
    }

    private void append(String text) {
        synchronized (pendingOutput) {
            if (disposed) return;
            pendingOutput.append(text);
            if (pendingOutput.length()>512*1024) pendingOutput.delete(0,pendingOutput.length()-256*1024);
            if (outputScheduled) return;
            outputScheduled=true;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            String pending;
            synchronized (pendingOutput) { pending=pendingOutput.toString(); pendingOutput.setLength(0); outputScheduled=false; }
            if (disposed) return;
            output.append(pending);
            if (output.getDocument().getLength()>512*1024) output.replaceRange("",0,output.getDocument().getLength()-256*1024);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    @Override public void dispose() {
        disposed=true; scanGeneration.incrementAndGet();
        if (importTask!=null) importTask.cancel(true);
        var process=runningProcess.getAndSet(null);
        if (process!=null) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        output.setText("");
        synchronized (pendingOutput) { pendingOutput.setLength(0); }
    }

}
