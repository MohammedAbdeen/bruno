package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

final class RequestEditor extends JPanel implements Disposable {
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
    private static final Gson JSON = new Gson();
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();
    final RequestDraft draft;
    private final Project project;
    private final Runnable onChange;
    private final Consumer<String> action;
    private final JTextField url = new JTextField();
    private final JComboBox<String> method = new JComboBox<>(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"});
    private final JLabel environmentBadge = new JLabel();
    private final JComboBox<String> environment = new JComboBox<>();
    private final JLabel preview = new JLabel(" ") {
        @Override public Dimension getMinimumSize() { return new Dimension(0,getPreferredSize().height); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
    };
    private final JLabel banner = new JLabel();
    private final JPanel bannerPanel = new JPanel(new BorderLayout(5, 3));
    private final JLabel activity = new JLabel();
    private final JPanel requestActions;
    private final JButton send;
    private final ResponseView response;
    private final KeyValueEditor query, path, headers, variables, form;
    private final EditorTextField body;
    private final EditorTextField preScript, postScript;
    private JButton formatBody, addBodyFile;
    private final JComboBox<String> bodyMode = new JComboBox<>(new String[]{"None", "JSON", "Text", "Form", "Multipart"});
    private final JComboBox<String> authMode = new JComboBox<>(new String[]{"None", "Bearer", "Basic", "API key"});
    private final JTextField authUser = new JTextField(), authKey = new JTextField();
    private final JPasswordField authValue = new JPasswordField();
    private final JComboBox<String> authLocation = new JComboBox<>(new String[]{"Header", "Query"});
    private final JCheckBox bypass = new JCheckBox("Invoke through development bridge (skip filters/interceptors)");
    private final JTextField principal = new JTextField();
    private final JCheckBox override = new JCheckBox("Override transport settings for this request");
    private final JSpinner connect = new JSpinner(new SpinnerNumberModel(15, 1, 120, 1));
    private final JSpinner total = new JSpinner(new SpinnerNumberModel(30, 1, 600, 1));
    private final JCheckBox redirects = new JCheckBox("Follow same-origin redirects");
    private final JPanel bodyCards = new JPanel(new CardLayout());
    private final javax.swing.Timer validateLater;
    private final javax.swing.Timer settingsWatch;
    private long settingsRevision = -1;
    private final JLabel defaultHeaders = new JLabel();
    private boolean loading = true;
    private volatile boolean disposed;
    private RequestExecution execution;
    private final JBSplitter split;
    private final JTabbedPane editorTabs = new BrunoTabbedPane();
    private final JComboBox<String> sectionPicker = new JComboBox<>();
    private final BrunoTabbedPane sections = new BrunoTabbedPane();
    private final JPanel request = new JPanel(new BorderLayout(6, 4));
    private final JPanel line = new JPanel();
    private boolean compact, responseTabs, adjustingSplit;
    private int validation;

    RequestEditor(Project project, RequestDraft draft, Runnable onChange, Consumer<String> action) {
        super(new BorderLayout(6, 6));
        this.project = project; this.draft = draft; this.onChange = onChange; this.action = action;
        response = new ResponseView(project);
        query = new KeyValueEditor(draft.fields.query, false, false, this::queryChanged);
        query.emptyText("No query parameters. Add a row or type ?key=value in the URL.");
        path = new KeyValueEditor(draft.fields.path, true, false, this::changed);
        headers = new KeyValueEditor(draft.fields.headers, false, false, this::changed);
        variables = new KeyValueEditor(draft.fields.variables, true, false, this::changed);
        variables.emptyText("No request-local variables. Add a row, or define shared values in Global Environments.");
        form = new KeyValueEditor(draft.fields.form, false, false, this::changed);
        body = new EditorTextField(draft.fields.body, project, FileTypeManager.getInstance().getFileTypeByExtension("json"));
        body.setOneLineMode(false);
        preScript = new EditorTextField(draft.fields.preScript, project, FileTypeManager.getInstance().getFileTypeByExtension("js"));
        postScript = new EditorTextField(draft.fields.postScript, project, FileTypeManager.getInstance().getFileTypeByExtension("js"));
        preScript.setOneLineMode(false); postScript.setOneLineMode(false);
        var scriptChanges = new DocumentListener() { @Override public void documentChanged(DocumentEvent event) { changed(); } };
        preScript.getDocument().addDocumentListener(scriptChanges,this); postScript.getDocument().addDocumentListener(scriptChanges,this);
        body.setPreferredSize(com.intellij.util.ui.JBUI.size(300, 160));
        body.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) { changed(); }
        }, this);
        send = BrunoUi.button("Send", AllIcons.Actions.Execute, () -> { if (execution == null) send(null); else execution.stop("Cancelled"); });
        send.putClientProperty("JButton.buttonType", "default");
        send.setToolTipText("Send Bruno Request — Ctrl+Enter / Cmd+Enter (configurable in Keymap)");
        settingsWatch = new javax.swing.Timer(1000, e -> {
            if (settingsRevision != ApiSettings.get(project).revision()) refreshEnvironments();
        });
        settingsWatch.start();
        validateLater = new javax.swing.Timer(200, e -> { onChange.run(); validatePreview(); }); validateLater.setRepeats(false);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        banner.setForeground(BrunoUi.AMBER);
        bannerPanel.add(banner, BorderLayout.CENTER);
        JButton manual = BrunoUi.button("Keep as manual", AllIcons.Actions.Edit, () -> {
            draft.endpointId = ""; draft.controller = ""; draft.detached = false; updateBanner(null);
        });
        bannerPanel.add(BrunoUi.toolbar(
                BrunoUi.button("Relink…", AllIcons.Actions.Refresh, () -> action.accept("relink")), manual), BorderLayout.EAST);
        top.add(bannerPanel);
        JButton more = BrunoUi.button("More…", null, null);
        more.addActionListener(e -> more(more));
        var saveDraft=BrunoUi.button("Save",AllIcons.Actions.MenuSaveall,this::save);
        saveDraft.setToolTipText("Save the current request draft locally");
        saveDraft.getAccessibleContext().setAccessibleName("Save request draft");
        requestActions=BrunoUi.toolbar(saveDraft,more);
        url.getAccessibleContext().setAccessibleName("Request URL");
        method.getAccessibleContext().setAccessibleName("HTTP method");
        preview.getAccessibleContext().setAccessibleName("Resolved request URL");
        requestActions.getAccessibleContext().setAccessibleName("Request actions");
        top.add(environmentBadge);
        top.add(line);
        preview.setFont(preview.getFont().deriveFont(Font.ITALIC));
        preview.setForeground(BrunoUi.MUTED);
        preview.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        top.add(preview);

        sections.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        sections.addTab("Params", query); sections.addTab("Path", path);
        JPanel headerPanel = new JPanel(new BorderLayout(4, 4));
        headerPanel.add(defaultHeaders, BorderLayout.NORTH); headerPanel.add(headers, BorderLayout.CENTER);
        sections.addTab("Headers", headerPanel); sections.addTab("Auth", authPanel());
        sections.addTab("Body", bodyPanel()); sections.addTab("Variables", variables);
        sections.addTab("Advanced", advanced());
        var scripts = new BrunoTabbedPane(); scripts.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        scripts.addTab("Pre-request",preScript); scripts.addTab("Post-response",postScript);
        JPanel hooks = new JPanel(new BorderLayout()); hooks.add(new JLabel("Bruno QuickJS hooks · req / res / bru · runtime configured in Bruno Settings"),BorderLayout.NORTH); hooks.add(scripts,BorderLayout.CENTER);
        sections.addTab("Scripts",hooks);
        for (int i = 0; i < sections.getTabCount(); i++)
            if (sections.getTitleAt(i).equals(draft.selectedSection)) sections.setSelectedIndex(i);
        for (int i = 0; i < sections.getTabCount(); i++) sectionPicker.addItem(sections.getTitleAt(i));
        sectionPicker.setSelectedIndex(sections.getSelectedIndex());
        sectionPicker.setToolTipText("Request section — all sections are available at narrow widths");
        sectionPicker.getAccessibleContext().setAccessibleName("Request section");
        sectionPicker.addActionListener(e -> sections.setSelectedIndex(sectionPicker.getSelectedIndex()));
        sections.addChangeListener(e -> {
            draft.selectedSection = sections.getTitleAt(sections.getSelectedIndex());
            sectionPicker.setSelectedIndex(sections.getSelectedIndex());
        });
        top.add(sectionPicker);
        // Mixed JLabel/JPanel alignments in BoxLayout otherwise shift the preview
        // towards the middle and needlessly truncate even ordinary URLs.
        for (var component:top.getComponents()) if (component instanceof JComponent control) control.setAlignmentX(Component.LEFT_ALIGNMENT);
        request.add(top, BorderLayout.NORTH); request.add(sections, BorderLayout.CENTER);

        split = new JBSplitter(true, (float) project.getService(WorkspaceState.class).editorProportion) {
            @Override public void doLayout() {
                if (getOrientation() && getFirstComponent() != null && getSecondComponent() != null && getHeight() > 0) {
                    Rectangle visible = getVisibleRect();
                    int bottom = visible.height > 0 ? Math.min(getHeight(), visible.y + visible.height) : getHeight();
                    int span = getHeight() - getDividerWidth();
                    float limit = span > 0 ? (float)(bottom - response.readableHeight()) / span : 1;
                    if (limit > 0 && getProportion() > limit) setAutomaticProportion(Math.max(0.2f,limit));
                }
                super.doLayout();
            }
        };
        split.setHonorComponentsMinimumSize(true);
        request.setMinimumSize(com.intellij.util.ui.JBUI.size(220, 240));
        response.setMinimumSize(com.intellij.util.ui.JBUI.size(220, 280));
        split.setShowDividerControls(false);
        split.setDividerWidth(com.intellij.util.ui.JBUI.scale(8));
        split.setShowDividerIcon(true);
        split.setAllowSwitchOrientationByMouseClick(false);
        split.getDivider().setOpaque(true);
        split.getDivider().setBackground(com.intellij.ui.JBColor.namedColor("Component.borderColor", new com.intellij.ui.JBColor(0xB8BBC0, 0x55585D)));
        split.getDivider().setToolTipText("Drag to resize request and response. Double-click to balance.");
        split.getDivider().addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) split.setProportion(0.55f); }
        });
        split.addPropertyChangeListener(JBSplitter.PROP_PROPORTION, e -> {
            float p = split.getProportion();
            if (!adjustingSplit && !responseTabs && split.getHeight() > 100 && p >= 0.2f && p <= 0.8f) {
                project.getService(WorkspaceState.class).editorProportion = p;
            }
        });

        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        editorTabs.addTab("Request", request);
        editorTabs.addTab("Response", response);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { reflowLayout(); }
        });
        reflowLayout();

        BrunoUi.onText(url, this::urlChanged);
        for (var field : List.of(authUser, authValue, authKey)) BrunoUi.onText(field, this::changed);
        method.addActionListener(e -> changed()); environment.addActionListener(e -> changed());
        authMode.addActionListener(e -> changed()); authLocation.addActionListener(e -> changed());
        bodyMode.addActionListener(e -> { selectBody(); changed(); });
        override.addActionListener(e -> { updateAdvanced(); changed(); });
        bypass.addActionListener(e -> changed()); BrunoUi.onText(principal,this::changed);
        redirects.addActionListener(e -> changed()); connect.addChangeListener(e -> changed()); total.addChangeListener(e -> changed());
        VariableSuggestions.install(url, () -> VariableSuggestions.names(ApiSettings.get(project), draft.fields));
        VariableSuggestions.install(body, project, this, () -> VariableSuggestions.names(ApiSettings.get(project), draft.fields));
        load();
        if (!draft.history.isEmpty()) response.showHistory(draft.history);
        else if (draft.response != null) response.show(draft.response);
    }
    private JPanel authPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        Runnable refresh = () -> {
            form.removeAll();
            String mode = Objects.toString(authMode.getSelectedItem());
            java.util.List<JComponent> rows = new ArrayList<>();
            rows.add(BrunoUi.labeled("Authentication", authMode));
            if (mode.equals("Basic")) rows.add(BrunoUi.labeled("Username", authUser));
            if (!mode.equals("None")) rows.add(BrunoUi.labeled(mode.equals("Basic") ? "Password" : mode.equals("Bearer") ? "Token" : "API key value", authValue));
            if (mode.equals("API key")) {
                rows.add(BrunoUi.labeled("API key name", authKey));
                rows.add(BrunoUi.labeled("Location", authLocation));
            }
            rows.add(new JLabel(mode.equals("None") ? "No authentication will be added to this request." : "Use {{token}} to reference an environment secret."));
            int y = 0;
            for (var row : rows) {
                var c = new GridBagConstraints(); c.gridx = 0; c.gridy = y++; c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
                c.insets = com.intellij.util.ui.JBUI.insets(4, 6); form.add(row, c);
            }
            var filler = new GridBagConstraints(); filler.gridy = y; filler.weighty = 1;
            form.add(Box.createGlue(), filler); form.revalidate(); form.repaint();
        };
        authMode.addActionListener(e -> refresh.run()); refresh.run();
        JPanel host = new JPanel(new BorderLayout()); host.add(new JScrollPane(form), BorderLayout.CENTER);
        return host;
    }
    private JPanel bodyPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        formatBody = BrunoUi.button("Format JSON", AllIcons.Actions.Edit, () -> {
            String current = body.getText();
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try {
                    String formatted = PRETTY_JSON.toJson(JsonParser.parseString(current));
                    SwingUtilities.invokeLater(() -> { if (!disposed && body.getText().equals(current)) body.setText(formatted); });
                } catch (Exception error) { SwingUtilities.invokeLater(() -> showError("The body is not valid JSON.")); }
            });
        });
        addBodyFile = BrunoUi.button("Add file", AllIcons.General.Add, () -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                RequestRow row = new RequestRow("file", chooser.getSelectedFile().getAbsolutePath());
                row.description = "file"; form.addRow(row); bodyMode.setSelectedItem("Multipart");
            }
        });
        panel.add(BrunoUi.toolbar(bodyMode, formatBody, addBodyFile), BorderLayout.NORTH);
        bodyCards.add(new JLabel("This request has no body."), "None");
        bodyCards.add(body, "Text"); bodyCards.add(form, "Form");
        panel.add(bodyCards, BorderLayout.CENTER);
        return panel;
    }
    private JPanel advanced() {
        JPanel panel = new JPanel(new GridBagLayout()); int y=0;
        for (JComponent row : new JComponent[]{bypass,BrunoUi.labeled("Bridge principal (optional)",principal),
                BrunoUi.button("Configure development bridge…",null,() -> new BridgeSettingsDialog(project).show()),
                override,BrunoUi.labeled("Connect timeout (seconds)",connect),BrunoUi.labeled("Total deadline (seconds)",total),redirects,
                BrunoUi.button("Open Bruno Settings",AllIcons.General.Settings,() -> action.accept("settings")),
                new JLabel("The deadline includes scripts, connecting, redirects and reading the response.")}) {
            var c = new GridBagConstraints(); c.gridx=0; c.gridy=y++; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL;
            c.insets=com.intellij.util.ui.JBUI.insets(4,6); panel.add(row,c);
        }
        var filler=new GridBagConstraints(); filler.gridy=y; filler.weighty=1; panel.add(Box.createGlue(),filler);
        JPanel host=new JPanel(new BorderLayout()); host.add(new JScrollPane(panel),BorderLayout.CENTER); return host;
    }
    private void selectBody() {
        String mode = Objects.toString(bodyMode.getSelectedItem());
        formatBody.setVisible(mode.equals("JSON"));
        addBodyFile.setVisible(mode.equals("Multipart"));
        ((CardLayout) bodyCards.getLayout()).show(bodyCards, mode.equals("JSON") ? "Text" : mode.equals("Multipart") ? "Form" : mode);
        body.setFileType(FileTypeManager.getInstance().getFileTypeByExtension(mode.equals("JSON") ? "json" : "txt"));
    }
    private void updateAdvanced() {
        for (JComponent component : new JComponent[]{connect, total, redirects}) component.setEnabled(override.isSelected());
    }
    private void load() {
        loading = true;
        method.setSelectedItem(draft.fields.method); url.setText(draft.fields.url);
        refreshEnvironments();
        preScript.setText(draft.fields.preScript); postScript.setText(draft.fields.postScript);
        bodyMode.setSelectedItem(draft.fields.bodyMode); body.setText(draft.fields.body);
        authMode.setSelectedItem(draft.fields.auth); authUser.setText(draft.fields.authUser); authValue.setText(draft.fields.authValue);
        authKey.setText(draft.fields.authKey); authLocation.setSelectedItem(draft.fields.authLocation);
        query.setRows(draft.fields.query); path.setRows(draft.fields.path); headers.setRows(draft.fields.headers);
        variables.setRows(draft.fields.variables); form.setRows(draft.fields.form);
        override.setSelected(draft.fields.overrideTransport); connect.setValue(draft.fields.connectSeconds);
        total.setValue(draft.fields.totalSeconds); redirects.setSelected(draft.fields.redirects);
        bypass.setSelected(draft.fields.bypassFilters); principal.setText(draft.fields.bridgePrincipal);
        updateAdvanced(); selectBody(); updateBanner(null);
        loading = false; validateLater.restart();
    }
    void refreshEnvironments() {
        boolean before = loading; loading = true;
        draft.fields.environment = ApiSettings.get(project).selected;
        environmentBadge.setText("Development bridge");
        environmentBadge.setVisible(!compact && draft.fields.bypassFilters);
        environment.removeAllItems();
        for (var env : ApiSettings.get(project).environments) environment.addItem(env.name);
        if (ApiSettings.get(project).environments.stream().noneMatch(e -> e.name.equals(draft.fields.environment))) {
            environment.addItem(draft.fields.environment);
        }
        environment.setSelectedItem(draft.fields.environment);
        loading = before;
        if (!loading) validateLater.restart();
    }
    private void changed() {
        if (loading || disposed) return;
        // Prevent table commit callbacks from recursively collecting the same editing cell.
        loading = true;
        draft.fields.method = Objects.toString(method.getSelectedItem());
        draft.fields.environment = Objects.toString(environment.getSelectedItem(), "");
        draft.fields.url = url.getText();
        draft.fields.headers = new ArrayList<>(headers.rows()); draft.fields.query = new ArrayList<>(query.rows());
        draft.fields.path = new ArrayList<>(path.rows()); draft.fields.variables = new ArrayList<>(variables.rows());
        draft.fields.form = new ArrayList<>(form.rows());
        draft.fields.preScript = preScript.getText(); draft.fields.postScript = postScript.getText();
        draft.fields.body = body.getText(); draft.fields.bodyMode = Objects.toString(bodyMode.getSelectedItem());
        draft.fields.auth = Objects.toString(authMode.getSelectedItem()); draft.fields.authUser = authUser.getText();
        draft.fields.authValue = new String(authValue.getPassword()); draft.fields.authKey = authKey.getText();
        draft.fields.authLocation = Objects.toString(authLocation.getSelectedItem());
        draft.fields.bypassFilters = bypass.isSelected(); draft.fields.bridgePrincipal = principal.getText();
        draft.fields.overrideTransport = override.isSelected(); draft.fields.connectSeconds = ((Number) connect.getValue()).intValue();
        draft.fields.totalSeconds = ((Number) total.getValue()).intValue(); draft.fields.redirects = redirects.isSelected();
        loading = false; validateLater.restart();
    }
    private void urlChanged() {
        if (loading) return;
        try {
            var parsed = QueryCodec.split(url.getText());
            loading = true;
            query.setRows(QueryCodec.merge(query.rows(), parsed.rows()));
            loading = false;
        } catch (IllegalArgumentException error) { loading = false; }
        changed();
    }
    private void queryChanged() {
        if (loading) return;
        try {
            var parsed = QueryCodec.split(url.getText());
            loading = true;
            url.setText(QueryCodec.join(parsed.base(), query.rows(), parsed.fragment()));
            loading = false;
        } catch (IllegalArgumentException error) { loading = false; }
        changed();
    }
    private void validatePreview() {
        if (disposed) return;
        settingsRevision = ApiSettings.get(project).revision();
        environmentBadge.setText("Development bridge");
        environmentBadge.setVisible(!compact && draft.fields.bypassFilters);
        if (execution == null) send.setText(draft.fields.bypassFilters ? "Invoke" : "Send");
        try {
            var settings = ApiSettings.get(project);
            if (draft.fields.bypassFilters && BrunoPreferences.bridge(project).bridgeTokenRef.isBlank()) throw new IllegalArgumentException("Configure the development bridge in Advanced before invoking.");
            String resolved = RequestComposer.preview(draft.fields, settings);
            var env = settings.environments.stream().filter(e -> e.name.equals(draft.fields.environment)).findFirst().orElseThrow();
            preview.setForeground(BrunoUi.MUTED);
            preview.setText("Resolved: " + resolved);
            preview.setToolTipText(resolved);
            url.getHighlighter().removeAllHighlights();
            defaultHeaders.setText(env.headerRows.stream().filter(r -> r.enabled).count()
                    + " environment default headers • request rows override by name");
            if (execution == null) send.setEnabled(true);
        } catch (Exception error) {
            preview.setForeground(BrunoUi.AMBER);
            preview.setText("⚠ " + (error.getMessage() == null ? "Check request variables." : error.getMessage()));
            preview.setToolTipText(error.getMessage());
            url.getHighlighter().removeAllHighlights();
            String message = Objects.toString(error.getMessage(), "");
            String missing = message.contains(": ") ? message.substring(message.lastIndexOf(": ") + 2) : "";
            var matcher = PLACEHOLDER.matcher(url.getText());
            while (!missing.isEmpty() && matcher.find()) if (missing.equals(matcher.group(1))) try {
                url.getHighlighter().addHighlight(matcher.start(), matcher.end(),
                        new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
                                new com.intellij.ui.JBColor(new Color(0xFFE4A1), new Color(0x725523))));
            } catch (javax.swing.text.BadLocationException ignored) { }
            if (execution == null) send.setEnabled(!draft.fields.preScript.isBlank() && message.startsWith("Missing "));
        }
    }
    @Override public void doLayout() {
        // A tab/container may assign new bounds during validation before its
        // queued ComponentEvent is delivered. Reflow against those real bounds.
        reflowLayout(); super.doLayout();
    }
    void reflowLayout() {
        if (disposed) return;
        // Recheck on hierarchy attachment as well as resize; detached editors can change width.
        boolean nextCompact = getWidth() < com.intellij.util.ui.JBUI.scale(550);
        Rectangle visible = getVisibleRect();
        int height = visible.height > 0 ? Math.min(getHeight(),visible.height) : getHeight();
        int stackedMinimum = Math.max(com.intellij.util.ui.JBUI.scale(560), request.getMinimumSize().height + response.readableHeight() + split.getDividerWidth());
        boolean nextResponseTabs = nextCompact || (height > 0 && height < stackedMinimum);
        if (nextCompact == compact && nextResponseTabs == responseTabs && getComponentCount() > 0) return;
        compact = nextCompact;
        responseTabs = nextResponseTabs;
        sectionPicker.setVisible(compact);
        sections.setTabsHidden(compact);
        environmentBadge.setVisible(!compact && draft.fields.bypassFilters);

        line.removeAll();
        if (compact) {
            JPanel urlRow = new JPanel(new BorderLayout(com.intellij.util.ui.JBUI.scale(4), 0));
            urlRow.add(method, BorderLayout.WEST);
            urlRow.add(url, BorderLayout.CENTER);
            JPanel sendRow = new JPanel(new BorderLayout(com.intellij.util.ui.JBUI.scale(4), 0));
            sendRow.add(requestActions, BorderLayout.WEST);
            sendRow.add(activity, BorderLayout.CENTER);
            sendRow.add(send, BorderLayout.EAST);
            line.setLayout(new BoxLayout(line, BoxLayout.Y_AXIS));
            line.add(urlRow);
            line.add(Box.createVerticalStrut(com.intellij.util.ui.JBUI.scale(4)));
            line.add(sendRow);
        } else {
            line.setLayout(new BorderLayout(com.intellij.util.ui.JBUI.scale(4), com.intellij.util.ui.JBUI.scale(3)));
            line.add(method, BorderLayout.WEST);
            line.add(url, BorderLayout.CENTER);
            JPanel commands=new JPanel(new BorderLayout());
            commands.add(BrunoUi.toolbar(activity,send),BorderLayout.WEST);
            commands.add(requestActions,BorderLayout.EAST);
            line.add(commands,BorderLayout.EAST);
        }

        removeAll();
        if (responseTabs) {
            split.setFirstComponent(null);
            split.setSecondComponent(null);
            if (editorTabs.indexOfComponent(request) < 0) editorTabs.addTab("Request", request);
            if (editorTabs.indexOfComponent(response) < 0) editorTabs.addTab("Response", response);
            add(editorTabs, BorderLayout.CENTER);
        } else {
            editorTabs.removeAll();
            split.setOrientation(!project.getService(WorkspaceState.class).sideBySide);
            split.setFirstComponent(request);
            split.setSecondComponent(response);
            float prop = (float) Math.max(0.2, Math.min(0.8,
                    project.getService(WorkspaceState.class).editorProportion > 0.05
                            ? project.getService(WorkspaceState.class).editorProportion : 0.55));
            setAutomaticProportion(draft.response != null && draft.response.status() == 0 ? Math.min(prop,0.5f) : prop);
            add(split, BorderLayout.CENTER);
        }
        updateResponseTitle();
        revalidate();
        repaint();
    }
    private void setAutomaticProportion(float proportion) {
        boolean previous = adjustingSplit; adjustingSplit = true;
        try { split.setProportion(proportion); } finally { adjustingSplit = previous; }
    }
    private void revealFailure() {
        reflowLayout();
        if (responseTabs) editorTabs.setSelectedComponent(response);
        else if (split.getOrientation()) setAutomaticProportion(Math.min(split.getProportion(),0.5f));
        revalidate(); repaint();
    }
    private void updateResponseTitle() {
        int index = editorTabs.indexOfComponent(response);
        if (index < 0) return;
        String status = execution != null ? "Sending…" : draft.response == null ? "No response"
                : draft.response.status() > 0 ? Integer.toString(draft.response.status()) : draft.response.outcome();
        editorTabs.setTitleAt(index, "Response (" + status + ")");
    }
    @Override public void addNotify() { super.addNotify(); reflowLayout(); }
    void send(Path download) {
        if (execution != null || disposed) return;
        changed(); validatePreview();
        if (!send.isEnabled()) return;
        final RequestDraft.Fields snapshot = JSON.fromJson(draft.snapshot(), RequestDraft.Fields.class);
        draft.lastController = draft.controller;
        draft.lastExecution = JSON.fromJson(JSON.toJson(snapshot), RequestDraft.Fields.class);
        final ApiSettings settings = JSON.fromJson(JSON.toJson(ApiSettings.get(project)), ApiSettings.class);
        settings.copySessions(ApiSettings.get(project));
        TransportOptions options;
        try { options = TransportOptions.effective(project, snapshot); }
        catch (Exception error) { showError(error.getMessage()); return; }
        if (responseTabs) editorTabs.setSelectedIndex(1);
        response.running(); send.setText("Stop"); send.setIcon(AllIcons.Actions.Suspend);
        activity.setIcon(com.intellij.ui.AnimatedIcon.Default.INSTANCE); activity.setText("Sending…");
        if (responseTabs) editorTabs.setTitleAt(1, "Response (Sending…)");
        String controller = draft.controller;
        var bridge = BrunoPreferences.bridge(project);
        String bridgeUrl = bridge.bridgeUrl, tokenRef = bridge.bridgeTokenRef;
        execution = new RequestExecution(result -> {
            if (disposed) return;
            draft.recordResponse(result); response.showHistory(draft.history); execution = null;
            if (result.status() == 0) { revealFailure(); BrunoNotifications.error(project, result.body()); }
            send.setText("Send"); send.setIcon(AllIcons.Actions.Execute); activity.setText(""); activity.setIcon(null);
            updateResponseTitle();
            onChange.run(); validatePreview();
        }, download, result -> ScriptRequest.post(project,snapshot,settings,result),
                (client,request) -> snapshot.bypassFilters ? BridgeClient.invoke(client,request,bridgeUrl,tokenRef,snapshot.bridgePrincipal,controller)
                        : client.send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream()));
        if (snapshot.bypassFilters) execution.transport("Development bridge — filters/interceptors bypassed; Spring binding and method/service advice retained");
        if (!snapshot.bypassFilters) {
            String profile = settings.environments.stream().filter(env -> env.name.equals(snapshot.environment)).map(env -> env.id).findFirst().orElse(snapshot.environment);
            var cookieSessions = project.getService(ApiCookies.class);
            execution.cookies(uri -> cookieSessions.jar(profile,uri));
        }
        execution.start(() -> ScriptRequest.prepare(project,snapshot,settings,options), options.totalSeconds());
        onChange.run();
    }
    boolean running() { return execution != null; }
    void focusUrl() {
        if (compact) editorTabs.setSelectedIndex(0);
        url.requestFocusInWindow();
    }
    void save() { changed(); draft.save(); onChange.run(); }
    void updateBanner(ApiEndpoint endpoint) {
        String message = draft.detached ? "Source endpoint no longer found. Your draft is preserved."
                : endpoint != null && (!endpoint.path().equals(draft.sourcePath) || !endpoint.method().equals(draft.sourceMethod))
                ? "Endpoint changed in source. Relink to review the new route or keep this draft." : "";
        banner.setText("<html>" + BrunoUi.html(message) + "</html>");
        bannerPanel.setVisible(!message.isEmpty());
    }
    private void more(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        item(menu, project.getService(WorkspaceState.class).sideBySide ? "Stack request and response" : "Show request and response side by side", () -> {
            var state = project.getService(WorkspaceState.class);
            state.sideBySide = !state.sideBySide;
            split.setOrientation(!state.sideBySide);
            revalidate(); repaint();
        });
        if (draft.lastExecution != null) item(menu, "Open last execution in new tab", () -> action.accept("duplicateLast"));
        item(menu, "Duplicate request", () -> action.accept("duplicate"));
        item(menu, draft.pinned ? "Unpin tab" : "Pin tab", () -> { draft.pinned = !draft.pinned; onChange.run(); });
        item(menu, "Revert to saved draft", () -> {
            if (JOptionPane.showConfirmDialog(this, "Discard edits since the last Save Draft?", "Revert",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) { draft.revert(); load(); onChange.run(); }
        });
        item(menu, "Open source", () -> action.accept("source"));
        item(menu, "Send & Download…", this::download);
        item(menu, "Clear session cookies for this environment", () -> {
            var settings=ApiSettings.get(project);
            String profile=settings.environments.stream().filter(env -> env.name.equals(draft.fields.environment)).map(env -> env.id).findFirst().orElse(draft.fields.environment);
            project.getService(ApiCookies.class).clear(profile);
            BrunoNotifications.info(project,"Session cookies cleared for this project and environment. Fetch a fresh CSRF token before logging in again.");
        });
        item(menu, "Close request", () -> action.accept("close"));
        item(menu, "Close other requests", () -> action.accept("closeOthers"));
        item(menu, "Reopen closed request", () -> action.accept("reopen"));
        item(menu, "Discard draft…", () -> action.accept("discard"));
        menu.show(invoker, 0, invoker.getHeight());
    }
    private static void item(JPopupMenu menu, String title, Runnable action) {
        JMenuItem item = new JMenuItem(title); item.addActionListener(e -> action.run()); menu.add(item);
    }
    private void download() {
        if (running()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Send request and stream response to a new file");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        if (Files.exists(file)) { showError("Choose a new filename; existing files are not replaced by downloads."); return; }
        if (JOptionPane.showConfirmDialog(this, "This sends the request again and streams its response to the chosen file. Continue?",
                "Send & Download", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) send(file);
    }
    private void showError(String message) { BrunoNotifications.error(project, message); }
    @Override public void dispose() {
        if (disposed) return;
        changed(); disposed = true; validateLater.stop(); settingsWatch.stop(); response.disposeView();
        if (execution != null) execution.stop("Cancelled");
    }
}
