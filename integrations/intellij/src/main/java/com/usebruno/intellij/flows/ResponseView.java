package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

final class ResponseView extends JPanel {
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Project project;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JLabel summary = new JLabel(" ");
    private final JLabel notice = new JLabel(" ");
    private final EditorTextField body, headers, details;
    private final com.intellij.ui.components.JBTextField search = new com.intellij.ui.components.JBTextField(12);
    private final JPanel formatTools;
    private final JPanel tools = new JPanel(new BorderLayout());
    private final JPanel heading = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new BrunoTabbedPane();
    private final JComboBox<String> renderer = new JComboBox<>(new String[]{"Auto", "Raw", "JSON", "Text", "XML", "HTML", "Image"});
    private final JLabel image = new JLabel();
    private final JEditorPane html = new JEditorPane("text/html", "");
    private final JTextArea transportError = new JTextArea();
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel bodyHost = new JPanel(bodyCards);
    private final JCheckBox wrap = new JCheckBox("Wrap lines");
    private final JCheckBox pretty = new JCheckBox("Pretty JSON", true);
    private ExecutionResult result;
    private java.util.List<ExecutionResult> history = java.util.List.of();
    private final JComboBox<String> historyPicker = new JComboBox<>();
    private boolean fillingHistory;
    private int generation;
    private boolean disposed;
    private java.util.concurrent.Future<?> renderTask;
    ResponseView(Project project) {
        super(new BorderLayout());
        this.project = project;
        body = viewer(project, "json"); headers = viewer(project, "txt"); details = viewer(project, "txt");
        search.getEmptyText().setText("Search response");
        search.getAccessibleContext().setAccessibleName("Search response");
        search.setToolTipText("Search response body (Enter for next match)");
        search.addActionListener(e -> {
            if (body.getEditor() == null || search.getText().isEmpty()) return;
            var editor = body.getEditor();
            String text = body.getText();
            int at = text.indexOf(search.getText(), editor.getSelectionModel().getSelectionEnd());
            if (at < 0) at = text.indexOf(search.getText());
            if (at >= 0) {
                editor.getSelectionModel().setSelection(at, at + search.getText().length());
                editor.getCaretModel().moveToOffset(at);
                editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE);
            }
        });
        var preferences=com.intellij.openapi.application.ApplicationManager.getApplication().getService(BrunoPreferences.class);
        wrap.setSelected(preferences.responseSoftWrap);
        wrap.setToolTipText("Wrap long response lines visually without changing copied or saved text");
        body.addSettingsProvider(editor -> editor.getSettings().setUseSoftWraps(wrap.isSelected()));
        wrap.addActionListener(event -> {
            preferences.responseSoftWrap=wrap.isSelected();
            if (body.getEditor()!=null) body.getEditor().getSettings().setUseSoftWraps(wrap.isSelected());
        });
        pretty.addActionListener(e -> renderBody());
        renderer.setToolTipText("Response viewer"); renderer.addActionListener(e -> renderBody());
        formatTools = BrunoUi.toolbar(renderer,pretty,wrap);
        JPanel textTools = BrunoUi.toolbar(search,
                BrunoUi.icon("Copy displayed body", AllIcons.Actions.Copy, this::copyBody),
                BrunoUi.icon("Save displayed response", AllIcons.Actions.MenuSaveall, this::save));
        Runnable reflowTools = () -> {
            boolean narrow = getWidth() < com.intellij.util.ui.JBUI.scale(550);
            String desired = narrow ? BorderLayout.NORTH : BorderLayout.WEST;
            var layout = (BorderLayout)tools.getLayout();
            if (desired.equals(layout.getConstraints(formatTools))) return;
            tools.removeAll(); tools.add(formatTools,desired); tools.add(textTools,narrow ? BorderLayout.SOUTH : BorderLayout.CENTER);
            tools.revalidate();
        };
        addComponentListener(new java.awt.event.ComponentAdapter() { @Override public void componentResized(java.awt.event.ComponentEvent event) { reflowTools.run(); } });
        reflowTools.run();
        JPanel bodyPanel = new JPanel(new BorderLayout(4, 4));
        transportError.setEditable(false); transportError.setLineWrap(true); transportError.setWrapStyleWord(true);
        transportError.setFont(UIManager.getFont("Label.font"));
        transportError.setBackground(com.intellij.ui.JBColor.namedColor("Editor.background", com.intellij.ui.JBColor.background()));
        transportError.setForeground(com.intellij.ui.JBColor.namedColor("Label.foreground", com.intellij.ui.JBColor.foreground()));
        transportError.setBorder(com.intellij.util.ui.JBUI.Borders.empty(8));
        bodyHost.add(body, "response"); bodyHost.add(new JScrollPane(transportError), "error");
        html.setEditable(false); html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        html.setBackground(transportError.getBackground()); html.setForeground(transportError.getForeground());
        image.setHorizontalAlignment(SwingConstants.CENTER);
        bodyHost.add(new JScrollPane(html), "html"); bodyHost.add(new JScrollPane(image), "image");
        bodyPanel.add(tools, BorderLayout.NORTH); bodyPanel.add(bodyHost, BorderLayout.CENTER);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Body", bodyPanel); tabs.addTab("Headers", headers); tabs.addTab("Details", details);

        JPanel contentPanel = new JPanel(new BorderLayout(4, 4));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        historyPicker.setToolTipText("Session history — latest 10 within 5 MiB; the newest response is always retained; cleared on closing");
        historyPicker.addActionListener(e -> {
            int index = historyPicker.getSelectedIndex();
            if (!fillingHistory && index >= 0 && index < history.size()) show(history.get(index));
        });
        heading.add(summary, BorderLayout.NORTH);
        JPanel responseActions = BrunoUi.toolbar(
                BrunoUi.button("Compare", null, () -> {
                    if (history.size() < 2) { BrunoNotifications.info(project, "Send the request twice to compare responses."); return; }
                    int selected = Math.max(1, historyPicker.getSelectedIndex());
                    var older = history.get(selected); var latest = history.getFirst();
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, new com.intellij.diff.requests.SimpleDiffRequest(
                            "Bruno response comparison", com.intellij.diff.DiffContentFactory.getInstance().create(older.body()),
                            com.intellij.diff.DiffContentFactory.getInstance().create(latest.body()), "Earlier response", "Latest response"));
                }),
                BrunoUi.button("Copy cURL (redacted)", null, () -> {
                    if (result == null || result.curl().isEmpty()) { BrunoNotifications.info(project, "No captured HTTP request is available yet."); return; }
                    BrunoUi.copy(result.curl(), notice, "cURL copied (credentials redacted)");
                }));
        JPopupMenu actionMenu = new JPopupMenu();
        for (var component : responseActions.getComponents()) if (component instanceof JButton button) {
            JMenuItem item = new JMenuItem(button.getText()); item.addActionListener(e -> button.doClick()); actionMenu.add(item);
        }
        JButton actions = BrunoUi.icon("Response history actions", AllIcons.Actions.More, null);
        actions.addActionListener(e -> actionMenu.show(actions, 0, actions.getHeight()));
        JPanel historyBar = new JPanel(new BorderLayout(com.intellij.util.ui.JBUI.scale(4), 0));
        historyPicker.setPrototypeDisplayValue("Latest · HTTP 200 · 123 ms");
        historyBar.add(BrunoUi.labeled("History", historyPicker), BorderLayout.CENTER); historyBar.add(actions, BorderLayout.EAST);
        heading.add(historyBar, BorderLayout.CENTER);
        contentPanel.add(heading, BorderLayout.NORTH);
        contentPanel.add(tabs, BorderLayout.CENTER);
        contentPanel.add(notice, BorderLayout.SOUTH);

        JPanel emptyPanel = new JPanel(new GridBagLayout());
        JPanel emptyInner = new JPanel();
        emptyInner.setLayout(new BoxLayout(emptyInner, BoxLayout.Y_AXIS));
        JLabel emptyIcon = new JLabel(AllIcons.Actions.Execute);
        emptyIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel emptyTitle = new JLabel("No response yet");
        emptyTitle.setFont(emptyTitle.getFont().deriveFont(Font.BOLD, 13f));
        emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel emptyDesc = new JLabel("Click Send or press Ctrl+Enter / Cmd+Enter to execute this request.");
        emptyDesc.setForeground(BrunoUi.MUTED);
        emptyDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyInner.add(emptyIcon);
        emptyInner.add(Box.createVerticalStrut(8));
        emptyInner.add(emptyTitle);
        emptyInner.add(Box.createVerticalStrut(4));
        emptyInner.add(emptyDesc);
        emptyPanel.add(emptyInner);

        cardHost.add(emptyPanel, "empty");
        cardHost.add(contentPanel, "content");
        add(cardHost, BorderLayout.CENTER);
        cards.show(cardHost, "empty");
    }
    int readableHeight() {
        // Count actual chrome and font metrics; a fixed pixel minimum is too
        // small with larger IDE fonts or a wrapped response toolbar.
        int line = transportError.getFontMetrics(transportError.getFont()).getHeight();
        return Math.max(com.intellij.util.ui.JBUI.scale(280), heading.getPreferredSize().height
                + tabs.getFontMetrics(tabs.getFont()).getHeight() + com.intellij.util.ui.JBUI.scale(16)
                + tools.getPreferredSize().height + notice.getPreferredSize().height
                + line * 4 + com.intellij.util.ui.JBUI.scale(32));
    }
    private static EditorTextField viewer(Project project, String extension) {
        var editor = new EditorTextField("", project, FileTypeManager.getInstance().getFileTypeByExtension(extension));
        editor.setOneLineMode(false); editor.setViewer(true);
        editor.setPreferredSize(com.intellij.util.ui.JBUI.size(300, 180)); return editor;
    }
    void running() {
        cards.show(cardHost, "content");
        summary.setText("Sending…");
        summary.setForeground(BrunoUi.AMBER);
        notice.setText("Previous response stays visible until this request completes.");
    }
    void showHistory(java.util.List<ExecutionResult> entries) {
        if (disposed) return;
        history = java.util.List.copyOf(entries); fillingHistory = true; historyPicker.removeAllItems();
        for (int i = 0; i < history.size(); i++) {
            var entry = history.get(i);
            historyPicker.addItem((i == 0 ? "Latest" : "Earlier " + i) + " · " + entry.outcome() + " · " + entry.durationMs() + " ms");
        }
        fillingHistory = false;
        if (!history.isEmpty()) { historyPicker.setSelectedIndex(0); show(history.getFirst()); }
    }
    void show(ExecutionResult response) {
        if (disposed) return;
        cards.show(cardHost, "content");
        result = response;
        boolean received = response.status() > 0;
        renderer.setEnabled(received);
        renderer.setVisible(received);
        formatTools.setVisible(received);
        if (!received) tabs.setSelectedIndex(0);
        bodyCards.show(bodyHost, received ? "response" : "error");
        transportError.setText(received ? "" : response.body()); transportError.setCaretPosition(0);
        summary.setText(response.outcome() + "  •  " + response.durationMs() + " ms  •  " + response.bytesRead() + " bytes read");
        summary.setForeground(response.status() >= 200 && response.status() < 400 ? BrunoUi.methodColor("GET")
                : response.status() >= 400 || response.status() == 0 ? BrunoUi.methodColor("DELETE") : BrunoUi.MUTED);
        headers.setText(response.headers()); details.setText(response.details());
        notice.setText(response.truncated() ? "Preview truncated. Use More → Send & Download for the full response."
                : response.download().isEmpty() ? " " : "Response saved to " + response.download());
        renderBody();
    }
    private void renderBody() {
        if (disposed || result == null) return;
        int current = ++generation;
        if (renderTask!=null) { renderTask.cancel(true); renderTask=null; }
        ExecutionResult response = result;
        String raw = response.body();
        String kind = "Auto".equals(renderer.getSelectedItem()) ? ResponseRendering.kind(response) : renderer.getSelectedItem().toString();
        boolean received = response.status() > 0;
        search.setVisible(received && !kind.equals("Image") && !kind.equals("HTML"));
        pretty.setVisible(received && (kind.equals("JSON") || kind.equals("XML")));
        wrap.setVisible(received && !kind.equals("Image") && !kind.equals("HTML"));
        pretty.setText(kind.equals("XML") ? "Format XML" : "Pretty JSON");
        image.setIcon(null);
        if (!received) { bodyCards.show(bodyHost, "error"); body.setText(raw); return; }
        bodyCards.show(bodyHost, "response"); body.setText(raw);
        body.setFileType(FileTypeManager.getInstance().getFileTypeByExtension(kind.equals("JSON") ? "json" : kind.equals("XML") ? "xml" : "txt"));
        if (kind.equals("Raw") || kind.equals("Text") || response.truncated()) { body.setText(raw); return; }
        boolean format = pretty.isSelected();
        renderTask=AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) return;
                if (kind.equals("Image")) {
                    var decoded = ResponseRendering.image(response.payload());
                    SwingUtilities.invokeLater(() -> { if (generation == current) { image.setIcon(new ImageIcon(decoded)); bodyCards.show(bodyHost, "image"); notice.setText(decoded.getWidth() + " × " + decoded.getHeight() + " image pixels; Save preserves the original bytes."); } });
                } else if (kind.equals("HTML")) {
                    String passive = ResponseRendering.html(raw);
                    SwingUtilities.invokeLater(() -> { if (generation == current) { html.setText(passive); html.setCaretPosition(0); bodyCards.show(bodyHost, "html"); notice.setText("Passive HTML preview: scripts, styles and external content are disabled. Preview capped at 200,000 characters."); } });
                } else {
                    String formatted = !format ? raw : kind.equals("XML") ? ResponseRendering.xml(raw) : PRETTY_JSON.toJson(FlowJsonImport.parseJson(raw));
                    SwingUtilities.invokeLater(() -> { if (generation == current) body.setText(formatted); });
                }
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (generation == current) { body.setText(raw); notice.setText("Preview unavailable; showing raw text. " + error.getClass().getSimpleName()); } });
            }
        });
    }
    private void copyBody() {
        if (result == null) return;
        if (ResponseRendering.kind(result).equals("Image") && image.getIcon() instanceof ImageIcon icon) {
            java.awt.datatransfer.Transferable contents = new java.awt.datatransfer.Transferable() {
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}; }
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) { return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor); }
                public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) throws java.awt.datatransfer.UnsupportedFlavorException { if (!isDataFlavorSupported(flavor)) throw new java.awt.datatransfer.UnsupportedFlavorException(flavor); return icon.getImage(); }
            };
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents,null); notice.setText("Image copied");
        } else BrunoUi.copy(body.getText(),notice,"Response copied");
    }
    private void save() {
        if (result == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save displayed response" + (result.truncated() ? " (truncated preview)" : ""));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace existing file?", "Save response",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String text = body.getText();
        String selected = renderer.getSelectedItem().toString();
        boolean saveImage = result.payloadSize() > 0 && (selected.equals("Image") || selected.equals("Auto") && ResponseRendering.kind(result).equals("Image"));
        byte[] bytes = saveImage ? result.payload() : null;
        if (!saveImage && selected.equals("Raw")) text = body.getText();
        final String savedText = text;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                if (bytes != null) Files.write(target, bytes); else Files.writeString(target, savedText, StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> notice.setText("Response saved."));
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { notice.setText("Could not save the response."); BrunoNotifications.error(project, "Could not save response. Check destination permissions and available disk space."); }); }
        });
    }
    void disposeView() {
        disposed=true; generation++;
        if (renderTask!=null) { renderTask.cancel(true); renderTask=null; }
        image.setIcon(null); html.setText(""); body.setText(""); headers.setText(""); details.setText("");
        history=java.util.List.of(); result=null;
        fillingHistory=true; historyPicker.removeAllItems(); fillingHistory=false;
    }
}
