package com.usebruno.intellij.flows;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.concurrency.CancellablePromise;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

final class ApiExplorerPanel extends JPanel implements Disposable {
    private record Group(String key, String label, Icon icon) { @Override public String toString() { return label; } }
    private final Project project;
    private final WorkspaceState state;
    private final JComboBox<String> globalEnvironment = new JComboBox<>();
    private boolean updatingEnvironment;
    private long environmentProfilesRevision=-1;
    private final JLabel environmentCaption=new JLabel("Global environment");
    private final JButton manageEnvironments=BrunoUi.button("Environments…",AllIcons.General.Filter,this::environments);
    private final com.intellij.ui.components.JBTextField search = new com.intellij.ui.components.JBTextField();
    private final JComboBox<String> modules = new JComboBox<>();
    private final JTree tree = new JTree(new DefaultMutableTreeNode("Discovering APIs…"));
    private final JLabel openingHint = new JLabel("Double-click an API or press Enter to open its request.");
    private final JLabel discoveryStatus = new JLabel("Waiting for indexing…");
    private final JButton refresh, stopScan;
    private final JPanel explorer = new JPanel(new BorderLayout(5, 5));
    private final JPanel workspace = new JPanel(new BorderLayout(5, 5));
    private final JPanel host = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new BrunoTabbedPane();
    private final JPanel empty = new JPanel(new GridBagLayout());
    private final JButton back;
    private final JButton viewEditor;
    private final javax.swing.Timer searchTimer;
    private final javax.swing.Timer discoveryTimer;
    private final JBSplitter split = new JBSplitter(false, 0.28f);
    private final Map<String, RequestEditor> editors = new LinkedHashMap<>();
    private List<ApiEndpoint> endpoints = List.of();
    private List<String> warnings = List.of();
    private CancellablePromise<SpringEndpointScanner.Result> scan;
    private boolean compact, changing, disposed, hasScanned;
    private boolean showExplorer = true;
    private boolean pendingDiscovery;
    private final java.util.function.BooleanSupplier discoveryVisible;
    private long scanId;
    private String relinkId;
    private String appliedSearch = "";

    ApiExplorerPanel(Project project) { this(project,null); }
    ApiExplorerPanel(Project project,java.util.function.BooleanSupplier visibility) {
        super(new BorderLayout(5, 5));
        this.project = project;
        discoveryVisible=visibility==null ? this::isShowing : visibility;
        state = project.getService(WorkspaceState.class);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        setBorder(JBUI.Borders.empty(6));
        refresh = BrunoUi.icon("Refresh APIs", AllIcons.Actions.Refresh, this::refresh);
        stopScan = BrunoUi.icon("Stop scanning", AllIcons.Actions.Suspend, this::stopScan);
        stopScan.setEnabled(false);
        back = BrunoUi.button("Back to APIs", AllIcons.Actions.Back, () -> { showExplorer = true; layoutWorkspace(true); tree.requestFocusInWindow(); });
        viewEditor = BrunoUi.button("Requests →", AllIcons.Actions.Forward, () -> { showExplorer = false; layoutWorkspace(true); });
        viewEditor.setVisible(false);
        JButton treeMore = BrunoUi.icon("More tree actions", AllIcons.Actions.More, null);
        treeMore.addActionListener(e -> showTreeMoreMenu(treeMore));

        modules.addItem("All modules"); search.setText(state.search);
        search.setToolTipText("Search method, path, controller or handler");
        search.getEmptyText().setText("Method, path or controller");
        search.getAccessibleContext().setAccessibleName("Search APIs");

        searchTimer = new javax.swing.Timer(150, e -> { state.search = search.getText(); filter(); });
        searchTimer.setRepeats(false);
        discoveryTimer = new javax.swing.Timer(500, e -> {
            if (discoveryVisible.getAsBoolean()) { pendingDiscovery=false; refresh(); }
            else pendingDiscovery=true;
        });
        discoveryTimer.setRepeats(false);
        com.intellij.psi.PsiManager.getInstance(project).addPsiTreeChangeListener(new com.intellij.psi.PsiTreeChangeAdapter() {
            private void changed(com.intellij.psi.PsiTreeChangeEvent event) {
                if (event.getFile() instanceof com.intellij.psi.PsiJavaFile
                        || event.getChild() instanceof com.intellij.psi.PsiJavaFile
                        || event.getOldChild() instanceof com.intellij.psi.PsiJavaFile
                        || event.getNewChild() instanceof com.intellij.psi.PsiJavaFile) scheduleDiscovery();
            }
            @Override public void childrenChanged(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
            @Override public void childAdded(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
            @Override public void childRemoved(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
            @Override public void childReplaced(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
            @Override public void childMoved(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
            @Override public void propertyChanged(com.intellij.psi.PsiTreeChangeEvent e) { changed(e); }
        }, this);
        project.getMessageBus().connect(this).subscribe(com.intellij.ProjectTopics.PROJECT_ROOTS,
                new com.intellij.openapi.roots.ModuleRootListener() {
                    @Override public void rootsChanged(com.intellij.openapi.roots.ModuleRootEvent event) { scheduleDiscovery(); }
                });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (!discoveryVisible.getAsBoolean() && discoveryTimer.isRunning()) { discoveryTimer.stop(); pendingDiscovery=true; }
            else if (discoveryVisible.getAsBoolean() && pendingDiscovery) scheduleDiscovery();
        });

        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchRow.add(BrunoUi.labeled("Search", search), BorderLayout.CENTER);
        searchRow.add(BrunoUi.icon("Clear Search", AllIcons.Actions.Close, () -> {
            searchTimer.stop();
            search.setText("");
            state.search = "";
            filter();
        }), BorderLayout.EAST);

        JPanel actionToolbar = BrunoUi.toolbar(
                refresh,
                BrunoUi.icon("New request", AllIcons.General.Add, this::newRequest),
                treeMore
        );

        JPanel top = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
        top.add(searchRow, BorderLayout.NORTH);
        JPanel filterRow = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        filterRow.add(BrunoUi.labeled("Module", modules), BorderLayout.CENTER);
        filterRow.add(actionToolbar, BorderLayout.SOUTH);
        top.add(filterRow, BorderLayout.CENTER);
        top.add(viewEditor, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(JBUI.Borders.empty(4, 2));
        bottom.add(discoveryStatus, BorderLayout.CENTER);
        openingHint.setVisible(!state.endpointOpened);
        bottom.add(openingHint, BorderLayout.SOUTH);
        explorer.add(top, BorderLayout.NORTH);
        explorer.add(new JScrollPane(tree), BorderLayout.CENTER);
        explorer.add(bottom, BorderLayout.SOUTH);

        tree.setRootVisible(true);
        tree.setBackground(JBColor.namedColor("Panel.background", JBColor.background()));
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                setBackgroundNonSelectionColor(t.getBackground());
                setTextNonSelectionColor(t.getForeground());
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                Object data = ((DefaultMutableTreeNode) value).getUserObject();
                if (data instanceof ApiEndpoint endpoint) {
                    Color color = BrunoUi.methodColor(endpoint.method());
                    setText("<html><b><font color='#" + String.format("%06x", color.getRGB() & 0xffffff) + "'>"
                            + endpoint.method() + "</font></b>&nbsp;&nbsp;" + BrunoUi.html(endpoint.path()) + "</html>");
                    setIcon(null);
                    setToolTipText(endpoint.controller() + "." + endpoint.handler() + " — Double-click or Enter to open");
                } else if (data instanceof Group group) { setIcon(group.icon()); setToolTipText(group.label()); }
                return this;
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        tree.getActionMap().put("open", new AbstractAction() { public void actionPerformed(ActionEvent e) { openSelected(); } });
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { rememberExpansion(); }
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { rememberExpansion(); }
        });

        JPanel workspaceTools = new JPanel(new BorderLayout());
        workspaceTools.add(back, BorderLayout.WEST);
        workspaceTools.add(BrunoUi.toolbar(
                BrunoUi.icon("New request", AllIcons.General.Add, this::newRequest),
                BrunoUi.icon("Bruno Settings", AllIcons.General.Settings, this::settings)), BorderLayout.EAST);
        workspace.add(workspaceTools, BorderLayout.NORTH);
        workspace.add(tabs, BorderLayout.CENTER);

        tabs.addChangeListener(e -> {
            int selected = tabs.getSelectedIndex();
            if (!changing && tabs.getSelectedComponent() instanceof RequestEditor editor) state.activeId = editor.draft.id;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component c = tabs.getTabComponentAt(i);
                if (c instanceof JPanel p && p.getComponentCount() > 0 && p.getComponent(0) instanceof JLabel l) {
                    boolean isSel = (i == selected);
                    l.setForeground(JBColor.namedColor("Label.foreground", new JBColor(0x333333, 0xDFE1E5)));
                    l.setFont(JBUI.Fonts.smallFont().deriveFont(isSel ? Font.BOLD : Font.PLAIN));
                }
            }
        });

        JPanel welcomeInner = new JPanel();
        welcomeInner.setLayout(new BoxLayout(welcomeInner, BoxLayout.Y_AXIS));
        JLabel welcomeIcon = new JLabel(AllIcons.Nodes.Services);
        welcomeIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel welcomeTitle = new JLabel("Bruno API Client");
        welcomeTitle.setFont(welcomeTitle.getFont().deriveFont(Font.BOLD, JBUI.scale(15f)));
        welcomeTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel welcomeSubtitle = new JLabel("Select an endpoint from the tree or create a new request.");
        welcomeSubtitle.setForeground(BrunoUi.MUTED);
        welcomeSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel welcomeActions = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(8), JBUI.scale(8)));
        welcomeActions.add(BrunoUi.primaryButton("New Request", AllIcons.General.Add, this::newRequest));
        welcomeActions.add(BrunoUi.button("Discover APIs", AllIcons.Actions.Refresh, this::refresh));
        welcomeActions.add(BrunoUi.button("Manage Environments", AllIcons.General.Filter, this::environments));
        welcomeActions.setAlignmentX(Component.CENTER_ALIGNMENT);

        welcomeInner.add(welcomeIcon);
        welcomeInner.add(Box.createVerticalStrut(JBUI.scale(8)));
        welcomeInner.add(welcomeTitle);
        welcomeInner.add(Box.createVerticalStrut(JBUI.scale(4)));
        welcomeInner.add(welcomeSubtitle);
        welcomeInner.add(Box.createVerticalStrut(JBUI.scale(12)));
        welcomeInner.add(welcomeActions);
        empty.add(welcomeInner);

        split.setHonorComponentsMinimumSize(false);
        split.setShowDividerControls(false);
        split.setShowDividerIcon(false);
        split.setDividerWidth(JBUI.scale(1));
        split.setFirstComponent(explorer);
        split.setSecondComponent(workspace);
        float initProp = (float) Math.max(0.15, Math.min(0.35, state.explorerProportion > 0.05 ? state.explorerProportion : 0.28));
        split.setProportion(initProp);
        split.addPropertyChangeListener(JBSplitter.PROP_PROPORTION, e -> {
            float p = split.getProportion();
            if (!compact && split.getWidth() > 100 && p >= 0.15f && p <= 0.85f) {
                state.explorerProportion = p;
            }
        });

        add(host, BorderLayout.CENTER);
        JPanel globalBar = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        var environmentControl=new JPanel(new BorderLayout(JBUI.scale(6),0));
        environmentCaption.setLabelFor(globalEnvironment);
        environmentControl.add(environmentCaption,BorderLayout.WEST); environmentControl.add(globalEnvironment,BorderLayout.CENTER);
        globalBar.add(environmentControl,BorderLayout.CENTER);
        manageEnvironments.setToolTipText("Manage IDE-wide environments, variables and default headers");
        manageEnvironments.getAccessibleContext().setAccessibleName("Manage global environments");
        globalBar.add(BrunoUi.toolbar(manageEnvironments),BorderLayout.EAST);
        globalEnvironment.getAccessibleContext().setAccessibleName("Global environment");
        globalEnvironment.setToolTipText("Shared by all API requests and projects in this IDE");
        add(globalBar, BorderLayout.NORTH);
        refreshGlobalEnvironment();
        globalEnvironment.addActionListener(e -> {
            if (updatingEnvironment || globalEnvironment.getSelectedItem() == null) return;
            var settings = ApiSettings.get(project);
            String selected = globalEnvironment.getSelectedItem().toString();
            if (!settings.selected.equals(selected)) { settings.selected = selected; settings.changed(); }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(ApiSettings.CHANGED, () -> {
            if (!disposed) refreshGlobalEnvironment();
        });
        addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent e) { layoutWorkspace(false); } });
        BrunoUi.onText(search, searchTimer::restart);
        modules.addActionListener(e -> { if (!changing) { state.module = Objects.toString(modules.getSelectedItem()); filter(); } });
        DataManager.registerDataProvider(this, key -> {
            if (disposed) return null;
            RequestEditor active=activeEditor();
            if (BrunoSendAction.EDITOR.is(key)) return active;
            if (BrunoCloseAction.CLOSE.is(key)) return active!=null ? (Runnable) () -> close(active.draft.id) : null;
            return null;
        });
        var global = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        var local = project.getService(ProjectPreferences.class);
        boolean restore = local.restoreTabs == null ? global.restoreTabs : local.restoreTabs;
        String active = state.activeId;
        for (var draft : state.drafts) if (draft.open && restore) open(draft);
        if (!restore) state.drafts.forEach(d -> d.open = false);
        if (editors.containsKey(active)) tabs.setSelectedComponent(editors.get(active));
        showExplorer = tabs.getTabCount() == 0; layoutWorkspace(true); scheduleDiscovery();
    }

    private void showTreeMoreMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem expand = new JMenuItem("Expand All", AllIcons.Actions.Expandall);
        expand.addActionListener(e -> { for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i); });
        JMenuItem collapse = new JMenuItem("Collapse All", AllIcons.Actions.Collapseall);
        collapse.addActionListener(e -> { for (int i = tree.getRowCount() - 1; i > 0; i--) tree.collapseRow(i); });
        JMenuItem notes = new JMenuItem("Discovery Notes", AllIcons.General.Information);
        notes.addActionListener(e -> notes());
        JMenuItem stop = new JMenuItem("Stop scanning", AllIcons.Actions.Suspend);
        stop.setEnabled(stopScan.isEnabled());
        stop.addActionListener(e -> stopScan());
        menu.add(stop);
        menu.addSeparator();
        JMenuItem export = new JMenuItem("Export OpenAPI…"); export.addActionListener(e -> exportOpenApi());
        menu.add(export);
        JMenuItem bridge = new JMenuItem("Development bridge…"); bridge.addActionListener(e -> new BridgeSettingsDialog(project).show()); menu.add(bridge);
        JMenuItem tasks = new JMenuItem("Scheduled / XXL tasks…"); tasks.addActionListener(e -> new BridgeTasksDialog(project).show()); menu.add(tasks);
        menu.addSeparator();
        menu.add(expand);
        menu.add(collapse);
        menu.addSeparator();
        menu.add(notes);
        menu.show(invoker, 0, invoker.getHeight());
    }

    private RequestEditor activeEditor() { return tabs.getSelectedComponent() instanceof RequestEditor editor ? editor : null; }
    @Override public void doLayout() {
        // Parent tab/tool-window layouts can assign bounds before the queued
        // resize event; decide explorer reflow from the bounds being laid out.
        layoutWorkspace(false); super.doLayout();
    }
    private void layoutWorkspace(boolean force) {
        if (disposed) return;
        boolean next = getWidth() < JBUI.scale(550);
        if (!force && next == compact) return;
        compact = next;
        environmentCaption.setText(compact ? "Env" : "Global environment");
        manageEnvironments.setText(compact ? "Manage…" : "Environments…");
        back.setVisible(compact);
        viewEditor.setVisible(compact && tabs.getTabCount() > 0);
        viewEditor.setText("Requests (" + tabs.getTabCount() + ") →");
        Component content = tabs.getTabCount() == 0 ? empty : tabs;
        if (content.getParent() != workspace) {
            workspace.remove(content == tabs ? empty : tabs);
            workspace.add(content, BorderLayout.CENTER);
        }
        Component desired = compact ? (showExplorer ? explorer : workspace) : split;
        // Removing a live EditorTextField disposes its native editor. Keep the
        // hierarchy attached when navigation has not actually changed layout.
        if (host.getComponentCount() == 1 && host.getComponent(0) == desired) return;
        if (compact) {
            split.setFirstComponent(null); split.setSecondComponent(null);
        }
        host.removeAll();
        if (!compact) {
            split.setFirstComponent(explorer); split.setSecondComponent(workspace);
            float safeProp = (float) Math.max(0.15, Math.min(0.35, state.explorerProportion > 0.05 ? state.explorerProportion : 0.28));
            split.setProportion(safeProp);
        }
        host.add(desired, BorderLayout.CENTER);
        host.revalidate(); host.repaint();
    }
    private void newRequest() {
        RequestDraft draft = new RequestDraft(); draft.fields.environment = ApiSettings.get(project).selected;
        draft.save(); state.drafts.add(draft); open(draft);
    }
    private void openSelected() {
        Object node = tree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode selected) || !(selected.getUserObject() instanceof ApiEndpoint endpoint)) return;
        if (relinkId != null) {
            var draft = state.drafts.stream().filter(d -> d.id.equals(relinkId)).findFirst().orElse(null);
            relinkId = null;
            if (draft != null) {
                draft.endpointId = endpoint.identity(); draft.controller = endpoint.controller(); draft.detached = false;
                draft.sourceUrl = endpoint.fileUrl(); draft.sourceOffset = endpoint.offset();
                draft.sourceMethod = endpoint.method(); draft.sourcePath = endpoint.path();
                if (JOptionPane.showConfirmDialog(this, "Update the draft's method and URL from this endpoint? Other edits are preserved.",
                        "Relink request", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    // Dispose first: its final UI flush must not overwrite the new route.
                    if (editors.containsKey(draft.id)) closeEditorOnly(draft.id);
                    RequestDraft fresh = new WorkspaceState().openEndpoint(endpoint, draft.fields.environment);
                    draft.fields.method = fresh.fields.method; draft.fields.url = fresh.fields.url;
                    draft.fields.path = fresh.fields.path; draft.fields.query.clear();
                }
                open(draft); return;
            }
        }
        open(state.openEndpoint(endpoint, ApiSettings.get(project).selected));
    }
    private void open(RequestDraft draft) {
        state.endpointOpened = true; openingHint.setVisible(false);
        draft.open = true; draft.lastUsed = System.currentTimeMillis(); state.prune(); state.activeId = draft.id; state.welcomeDismissed = true;
        RequestEditor editor = editors.get(draft.id);
        if (editor == null) {
            editor = new RequestEditor(project, draft, () -> updateTitle(draft.id), command -> command(draft.id, command));
            editors.put(draft.id, editor); Disposer.register(this, editor); tabs.addTab(draft.name, editor);
            int index = tabs.indexOfComponent(editor);
            JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            title.setOpaque(false);
            title.setBorder(JBUI.Borders.empty(1, 2));
            JLabel label = new JLabel();
            label.setFont(JBUI.Fonts.smallFont().deriveFont(Font.BOLD));
            label.setForeground(JBColor.namedColor("TabbedPane.selectedForeground", JBColor.WHITE));
            title.add(label);
            JButton close = new JButton(AllIcons.Actions.Close);
            close.setToolTipText("Close request (draft is retained)");
            close.setPreferredSize(JBUI.size(16, 16));
            close.setBorder(JBUI.Borders.empty());
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.setOpaque(false);
            close.setFocusable(false);
            close.addActionListener(e -> close(draft.id));
            title.add(close);

            MouseAdapter tabClick = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int idx = tabs.indexOfTabComponent(title);
                    if (idx >= 0) {
                        if (SwingUtilities.isMiddleMouseButton(e)) close(draft.id);
                        else tabs.setSelectedIndex(idx);
                    }
                }
            };
            title.addMouseListener(tabClick);
            label.addMouseListener(tabClick);
            tabs.setTabComponentAt(index, title);
        }
        tabs.setSelectedComponent(editor); updateTitle(draft.id); showExplorer = false; layoutWorkspace(true);
        RequestEditor focused = editor; SwingUtilities.invokeLater(() -> {
            if (!disposed && editors.get(draft.id)==focused && tabs.getSelectedComponent()==focused) focused.focusUrl();
        });
    }
    private void updateTitle(String id) {
        var editor = editors.get(id); if (editor == null) return;
        int index = tabs.indexOfComponent(editor); if (index < 0) return;
        var draft = editor.draft;
        String path = draft.name.length() > 38 ? draft.name.substring(0, 35) + "…" : draft.name;
        String text = (draft.dirty() ? "• " : "") + (draft.pinned ? "◆ " : "") + draft.fields.method + " " + path;
        tabs.setTitleAt(index, text); tabs.setToolTipTextAt(index, draft.fields.url);
        JPanel header = (JPanel) tabs.getTabComponentAt(index);
        if (header != null && header.getComponentCount() > 0 && header.getComponent(0) instanceof JLabel label) {
            label.setText(text);
            boolean isSelected = tabs.getSelectedIndex() == index;
            label.setForeground(JBColor.namedColor("Label.foreground", new JBColor(0x333333, 0xDFE1E5)));
            label.setFont(JBUI.Fonts.smallFont().deriveFont(isSelected ? Font.BOLD : Font.PLAIN));
            label.setIcon(editor.running() ? com.intellij.ui.AnimatedIcon.Default.INSTANCE : null);
            header.revalidate();
            header.repaint();
        }
    }
    private void close(String id) {
        var editor = editors.get(id); if (editor == null) return;
        var draft = editor.draft; closeEditorOnly(id); state.close(draft);
        if (tabs.getTabCount() == 0) showExplorer = true;
        layoutWorkspace(true);
    }
    private void closeEditorOnly(String id) {
        var editor = editors.remove(id); if (editor == null) return;
        tabs.remove(editor); Disposer.dispose(editor);
    }
    private void command(String id, String command) {
        var editor = editors.get(id); if (editor == null) return;
        var draft = editor.draft;
        switch (command) {
            case "duplicateLast" -> { var copy = draft.duplicateLastExecution(); state.drafts.add(copy); open(copy); }
            case "duplicate" -> { var copy = draft.duplicate(); state.drafts.add(copy); open(copy); }
            case "close" -> close(id);
            case "closeOthers" -> new ArrayList<>(editors.keySet()).stream().filter(key -> !key.equals(id) && !editors.get(key).draft.pinned).forEach(this::close);
            case "reopen" -> state.drafts.stream().filter(d -> !d.open).max(Comparator.comparingLong(d -> d.lastUsed)).ifPresent(this::open);
            case "discard" -> {
                if (JOptionPane.showConfirmDialog(this, "Permanently discard this local draft?", "Discard Draft",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) { close(id); state.drafts.remove(draft); }
            }
            case "source" -> {
                var file = VirtualFileManager.getInstance().findFileByUrl(draft.sourceUrl);
                if (file != null) new OpenFileDescriptor(project, file, draft.sourceOffset).navigate(true);
            }
            case "relink" -> { relinkId = id; discoveryStatus.setText("Choose an endpoint and press Enter to relink this draft."); showExplorer = true; layoutWorkspace(true); tree.requestFocusInWindow(); }
            case "settings" -> settings();
            case "environments" -> environments();
        }
    }
    void openEndpoint(ApiEndpoint endpoint) {
        open(state.openEndpoint(endpoint, ApiSettings.get(project).selected));
    }
    private void refreshGlobalEnvironment() {
        var settings = ApiSettings.get(project);
        if (environmentProfilesRevision==settings.profilesRevision()) return;
        environmentProfilesRevision=settings.profilesRevision();
        updatingEnvironment = true;
        globalEnvironment.removeAllItems(); settings.environments.forEach(e -> globalEnvironment.addItem(e.name));
        globalEnvironment.setSelectedItem(settings.selected);
        updatingEnvironment = false;
    }
    private void environments() { new EnvironmentDialog(project, () -> editors.values().forEach(RequestEditor::refreshEnvironments)).show(); }
    private void settings() { ShowSettingsUtil.getInstance().editConfigurable(project, new BrunoConfigurable(project)); }
    private void notes() {
        JTextArea text = new JTextArea("Java Spring annotation controllers are supported. Runtime, Kotlin, and custom composed routes may need review.\n\n"
                + String.join("\n", warnings), 14, 65);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(new JScrollPane(text), text)
                .setTitle("Discovery notes").setMovable(true).setResizable(true).setRequestFocus(true).createPopup().showInFocusCenter();
    }
    private void exportOpenApi() {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("openapi.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var target = chooser.getSelectedFile().toPath();
        if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace " + target + "?", "Export OpenAPI", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        var snapshot = List.copyOf(endpoints);
        var settings = ApiSettings.get(project);
        String baseUrl = settings.environments.stream().filter(e -> e.name.equals(settings.selected)).map(e -> e.baseUrl).findFirst().orElse("http://localhost:8080");
        ReadAction.nonBlocking(() -> OpenApiExport.create(project, snapshot, baseUrl)).inSmartMode(project).expireWith(this)
                .finishOnUiThread(ModalityState.any(), json -> AppExecutorUtil.getAppExecutorService().execute(() -> {
                    try { java.nio.file.Files.writeString(target, json); BrunoNotifications.info(project, "Exported OpenAPI to " + target); }
                    catch (Exception error) { BrunoNotifications.error(project, "Could not save OpenAPI. Check destination permissions."); }
                })).submit(AppExecutorUtil.getAppExecutorService());
    }
    void scheduleDiscovery() {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            pendingDiscovery=true;
            if (discoveryVisible.getAsBoolean()) discoveryTimer.restart(); else discoveryTimer.stop();
        });
    }
    private void refresh() {
        if (scan != null && !scan.isDone()) { discoveryTimer.restart(); return; }
        long current = ++scanId;
        discoveryStatus.setText(hasScanned ? "Refreshing APIs… previous results remain available." : "Discovering APIs / waiting for indexing…");
        discoveryStatus.setIcon(com.intellij.ui.AnimatedIcon.Default.INSTANCE);
        refresh.setEnabled(false); stopScan.setEnabled(true);
        scan = ReadAction.nonBlocking(() -> project.getService(EndpointDiscovery.class).scan()).inSmartMode(project).expireWith(this)
                .finishOnUiThread(ModalityState.any(), result -> {
                    if (disposed || current != scanId) return;
                    showDiscovery(result);
                }).submit(AppExecutorUtil.getAppExecutorService());
        scan.onError(error -> SwingUtilities.invokeLater(() -> {
            if (disposed || current != scanId) return;
            discoveryStatus.setIcon(null); discoveryStatus.setText("Discovery failed. Previous results retained; Refresh to retry.");
            refresh.setEnabled(true); stopScan.setEnabled(false);
        }));
    }
    void showDiscovery(SpringEndpointScanner.Result result) {
        hasScanned = true; endpoints = result.endpoints(); warnings = result.warnings();
        changing = true; modules.removeAllItems(); modules.addItem("All modules");
        endpoints.stream().map(ApiEndpoint::module).distinct().sorted().forEach(modules::addItem);
        modules.setSelectedItem(state.module);
        if (modules.getSelectedIndex() < 0) modules.setSelectedIndex(0);
        changing = false; state.module = Objects.toString(modules.getSelectedItem());
        refresh.setEnabled(true); stopScan.setEnabled(false); discoveryStatus.setIcon(null); filter();
        if (endpoints.isEmpty() && warnings.stream().anyMatch(w -> w.startsWith("Spring Web annotations"))) {
            discoveryStatus.setText("Spring Web not found — import project dependencies. See Notes.");
        }
        for (var draft : state.drafts) if (!draft.endpointId.isEmpty()) {
            var endpoint = endpoints.stream().filter(e -> e.identity().equals(draft.endpointId)).findFirst().orElse(null);
            draft.detached = endpoint == null;
            if (endpoint != null && draft.controller.isBlank()) draft.controller = endpoint.controller();
            var editor = editors.get(draft.id); if (editor != null) editor.updateBanner(endpoint);
        }
    }
    void stopScan() {
        pendingDiscovery=false; discoveryTimer.stop();
        scanId++; if (scan != null) scan.cancel();
        discoveryStatus.setIcon(null); discoveryStatus.setText("Discovery cancelled. Previous results retained.");
        refresh.setEnabled(true); stopScan.setEnabled(false);
    }
    private void filter() {
        if (changing || disposed) return;
        rememberExpansion(); List<String> expanded = new ArrayList<>(state.expanded);
        Object selectedObject = tree.getLastSelectedPathComponent();
        String selection = selectedObject instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ApiEndpoint e ? e.identity() : "";
        var root = new DefaultMutableTreeNode(new Group("project", project.getName(), AllIcons.Nodes.Module));
        Map<String, DefaultMutableTreeNode> moduleNodes = new LinkedHashMap<>(), controllers = new LinkedHashMap<>();
        String rawSearch = search.getText().trim().toLowerCase(Locale.ROOT);
        String[] words = rawSearch.isEmpty() ? new String[0] : rawSearch.split("\\s+");
        List<ApiEndpoint> scope = endpoints.stream().filter(e -> state.module.equals("All modules") || e.module().equals(state.module)).toList();
        TreePath selectedPath = null;
        int matches = 0;
        for (var endpoint : scope) {
            if (words.length > 0) {
                String target = endpoint.searchableLower();
                boolean allMatch = true;
                for (String word : words) {
                    if (!target.contains(word)) { allMatch = false; break; }
                }
                if (!allMatch) continue;
            }
            matches++;
            var module = moduleNodes.computeIfAbsent(endpoint.module(), key -> {
                var node = new DefaultMutableTreeNode(new Group("module:" + key, key, AllIcons.Nodes.Module)); root.add(node); return node;
            });
            var controller = controllers.computeIfAbsent(endpoint.module() + "|" + endpoint.controller(), key -> {
                var node = new DefaultMutableTreeNode(new Group("controller:" + key, endpoint.simpleController(), AllIcons.Nodes.Class)); module.add(node); return node;
            });
            var endpointNode = new DefaultMutableTreeNode(endpoint); controller.add(endpointNode);
            if (endpoint.identity().equals(selection)) selectedPath = new TreePath(endpointNode.getPath());
        }
        for (var node : controllers.values()) {
            Group group = (Group) node.getUserObject(); node.setUserObject(new Group(group.key(), group.label() + " (" + node.getChildCount() + ")", group.icon()));
        }
        for (var node : moduleNodes.values()) {
            int count = 0; for (int i = 0; i < node.getChildCount(); i++) count += node.getChildAt(i).getChildCount();
            Group group = (Group) node.getUserObject(); node.setUserObject(new Group(group.key(), group.label() + " (" + count + (count == 1 ? " API)" : " APIs)"), group.icon()));
        }
        changing = true; tree.setModel(new DefaultTreeModel(root));
        tree.expandPath(new TreePath(root.getPath()));
        // Restore only group paths; endpoint leaves never need expansion.
        // Empty saved state is a deliberate collapse after the first model.
        boolean firstModel = !state.treeInitialized;
        for (var node : moduleNodes.values()) {
            Group group = (Group) node.getUserObject();
            if (firstModel || expanded.contains(group.key()) || !rawSearch.isEmpty()) tree.expandPath(new TreePath(node.getPath()));
        }
        for (var node : controllers.values()) {
            Group group = (Group) node.getUserObject();
            if (expanded.contains(group.key()) || !rawSearch.isEmpty()) tree.expandPath(new TreePath(node.getPath()));
        }
        if (!moduleNodes.isEmpty()) state.treeInitialized = true;
        appliedSearch = rawSearch;
        if (selectedPath != null) tree.setSelectionPath(selectedPath);
        changing = false;
        if (hasScanned) discoveryStatus.setText(endpoints.isEmpty() ? "No supported controllers. Import the Java project, or create a request."
                : matches == 0 ? "No matches. Clear Search or choose All modules."
                : (rawSearch.isEmpty() ? Integer.toString(matches) : matches + " of " + scope.size()) + (scope.size() == 1 ? " API" : " APIs")
                + (warnings.isEmpty() ? "" : " • " + warnings.size() + (warnings.size() == 1 ? " note" : " notes")));
    }
    private void rememberExpansion() {
        if (changing || !appliedSearch.isEmpty() || tree.getModel().getRoot() == null) return;
        var expanded = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        if (expanded == null) { state.expanded.clear(); return; }
        List<String> keys = new ArrayList<>();
        while (expanded.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) expanded.nextElement().getLastPathComponent();
            if (node.getUserObject() instanceof Group group) keys.add(group.key());
        }
        state.expanded = keys;
    }
    @Override public void dispose() {
        if (disposed) return;
        rememberExpansion(); disposed = true; if (scan != null) scan.cancel();
        searchTimer.stop(); discoveryTimer.stop();
        ToolTipManager.sharedInstance().unregisterComponent(tree);
        // Editors and listener connections are children in the Disposer tree.
        editors.clear(); changing=true; tabs.removeAll(); changing=false;
        DataManager.removeDataProvider(this);
    }
}
