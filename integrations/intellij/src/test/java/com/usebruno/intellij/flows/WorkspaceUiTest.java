package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.UIUtil;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public class WorkspaceUiTest extends BrunoPlatformTestCase {
    private ApiEndpoint endpoint(String path, String module, String handler) {
        return new ApiEndpoint("GET", path, "demo.UsersController", handler, "file:///Users.java", 10,
                module, "file:///Users.java", handler + "()", 0);
    }
    public void testTabsRestoreAndResponsiveBackPreservesDrafts() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var a = state.openEndpoint(endpoint("/users", "Users", "list"), "Local");
        var b = state.openEndpoint(endpoint("/users/{id}", "Users", "get"), "Local");
        a.fields.body = "draft-a"; b.fields.body = "draft-b";
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 549);
            JButton back = components(panel, JButton.class).stream().filter(button -> "Back to APIs".equals(button.getText())).findFirst().orElseThrow();
            assertTrue(back.isVisible()); back.doClick();
            assertEquals("draft-a", a.fields.body); assertEquals("draft-b", b.fields.body);
            assertTrue(a.dirty()); assertTrue(b.dirty());
            resize(panel, 550);
            back = components(panel, JButton.class).stream().filter(button -> "Back to APIs".equals(button.getText())).findFirst().orElseThrow();
            assertFalse(back.isVisible());
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testEditorCellCommitAndBulkStateDoNotLoseRows() {
        final KeyValueEditor[] holder = new KeyValueEditor[1];
        holder[0] = new KeyValueEditor(List.of(new RequestRow("x", "first")), false, false, () -> holder[0].rows());
        var editor = holder[0];
        assertTrue(editor.table.editCellAt(0, 2));
        ((JTextField) editor.table.getEditorComponent()).setText("changed");
        editor.commit(); UIUtil.dispatchAllInvocationEvents();
        assertEquals("changed", editor.rows().getFirst().value);
    }
    public void testWorkspaceLayoutsRender() throws Exception {
        var previousLaf = UIManager.getLookAndFeel();
        boolean previousDark = !com.intellij.ui.JBColor.isBright();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf());
        com.intellij.ui.JBColor.setDark(true);
        com.intellij.openapi.util.IconLoader.activate();
        com.intellij.openapi.util.IconLoader.setUseDarkIcons(true);
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var endpoints = List.of(endpoint("/api/users", "UserService", "list"),
                endpoint("/api/users/{id}", "UserService", "get"), endpoint("/api/orders", "OrderService", "orders"));
        var draft = state.openEndpoint(endpoints.getFirst(), "Local");
        draft.fields.headers.add(new RequestRow("X-Client", "Bruno"));
        draft.response = new ExecutionResult(200, "HTTP 200", 142, 38, "{\"users\":[{\"id\":42,\"name\":\"Bruno\"}]}",
                "content-type: application/json", "GET http://localhost:8080/api/users\nEnvironment: Local", false, false, "");
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 1200);
            panel.stopScan();
            panel.showDiscovery(new SpringEndpointScanner.Result(endpoints, List.of()));
            panel.addNotify();
            var requestSections = components(panel, JTabbedPane.class).stream().filter(t -> t.indexOfTab("Auth") >= 0).findFirst().orElseThrow();
            requestSections.setSelectedIndex(requestSections.indexOfTab("Auth"));
            for (int width : new int[]{1200, 420}) {
                resize(panel, width); UIUtil.dispatchAllInvocationEvents(); layout(panel);
                if (Boolean.getBoolean("bruno.guiTests")) com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                var image = new BufferedImage(width, 850, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                String directory = System.getProperty("bruno.screenshotDir");
                if (directory != null) {
                    Path path = Path.of(directory); Files.createDirectories(path);
                    ImageIO.write(image, "png", path.resolve("workspace-" + width + ".png").toFile());
                }
            }
            var flowsPanel = new BrunoFlowsPanel(getProject()); Disposer.register(panel,flowsPanel);
            resize(flowsPanel, 600); UIUtil.dispatchAllInvocationEvents(); layout(flowsPanel);
            var flowsImage = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D gFlows = flowsImage.createGraphics(); flowsPanel.paint(gFlows); gFlows.dispose();
            String directory = System.getProperty("bruno.screenshotDir");
            if (directory != null) {
                ImageIO.write(flowsImage, "png", Path.of(directory).resolve("flows-empty.png").toFile());
            }
        } finally {
            panel.removeNotify(); Disposer.dispose(panel); state.drafts.clear();
            UIManager.setLookAndFeel(previousLaf);
            com.intellij.ui.JBColor.setDark(previousDark);
            com.intellij.openapi.util.IconLoader.setUseDarkIcons(previousDark);
            com.intellij.openapi.util.IconLoader.deactivate();
        }
    }
    public void testCloseShortcutIsScopedToBrunoFocus() {
        var action = new BrunoCloseAction();
        int[] closed = {0};
        var panel = new ApiExplorerPanel(getProject());
        try {
            var inside = new JTextField(); panel.add(inside);
            for (var context : new Component[]{inside, new JTextField()}) {
                var data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(BrunoCloseAction.CLOSE, () -> closed[0]++)
                        .add(com.intellij.openapi.actionSystem.PlatformDataKeys.CONTEXT_COMPONENT, context).build();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "test", data);
                action.update(event);
                assertEquals(context == inside, event.getPresentation().isEnabled());
                action.actionPerformed(event);
            }
            assertEquals(1, closed[0]);
        } finally { Disposer.dispose(panel); }
    }
    public void testRestoredSectionResponseAndWideOrientation() {
        var draft = new RequestDraft(); draft.selectedSection = "Body";
        draft.response = new ExecutionResult(201, "HTTP 201", 1, 2, "{}", "", "", false, false, "");
        var state = getProject().getService(WorkspaceState.class); state.sideBySide = true;
        var editor = new RequestEditor(getProject(), draft, () -> {}, command -> {});
        try {
            resize(editor, 1200);
            assertFalse(components(editor, com.intellij.ui.JBSplitter.class).getFirst().getOrientation());
            var sections = components(editor, JTabbedPane.class).stream().filter(t -> t.indexOfTab("Body") >= 0).findFirst().orElseThrow();
            assertEquals("Body", sections.getTitleAt(sections.getSelectedIndex()));
            sections.setSelectedIndex(2); assertEquals("Headers", draft.selectedSection);
            resize(editor, 420);
            assertTrue(components(editor, JTabbedPane.class).stream().anyMatch(t -> t.indexOfTab("Response (201)") >= 0));
        } finally { Disposer.dispose(editor); state.sideBySide = false; }
    }
    public void testReopeningActiveRequestDoesNotDetachWorkspace() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var endpoint = endpoint("/api/stable", "Users", "stable");
        state.openEndpoint(endpoint, "Local");
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 1200); panel.stopScan();
            panel.showDiscovery(new SpringEndpointScanner.Result(List.of(endpoint), List.of()));
            var editor = components(panel, RequestEditor.class).getFirst();
            int[] reparented = {0};
            editor.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.PARENT_CHANGED) != 0) reparented[0]++;
            });
            var tree = components(panel, JTree.class).getFirst();
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            tree.setSelectionRow(tree.getRowCount() - 1);
            tree.getActionMap().get("open").actionPerformed(new java.awt.event.ActionEvent(tree, 0, "open"));
            assertSame(editor, components(panel, RequestEditor.class).getFirst());
            assertEquals("Reopening must preserve native editor hierarchy", 0, reparented[0]);
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testResponseStaysReadableWithSavedLargeRequestPaneAndShortWindow() throws Exception {
        var state = getProject().getService(WorkspaceState.class);
        double previous = state.editorProportion; state.editorProportion = 0.8;
        var draft = new RequestDraft(); draft.selectedSection="Body"; draft.fields.bodyMode="JSON";
        draft.response = new ExecutionResult(0,"Connection error",5,0,"Could not connect to localhost:8080. Check Base URL in Global Environments.","","",false,false,"");
        var editor = new RequestEditor(getProject(),draft,() -> {},command -> {});
        try {
            resize(editor,1000);
            var splitter = components(editor,com.intellij.ui.JBSplitter.class).getFirst();
            var response = components(editor,ResponseView.class).getFirst();
            assertTrue("Response needs room for its controls and error", response.getHeight() >= com.intellij.util.ui.JBUI.scale(280));
            assertTrue(splitter.getDividerWidth() >= com.intellij.util.ui.JBUI.scale(8));
            assertNotNull(splitter.getDivider().getToolTipText());
            var message = components(response,JTextArea.class).getFirst();
            assertTrue(message.isVisible()); assertTrue(message.getHeight() > com.intellij.util.ui.JBUI.scale(60));
            String screenshotDir = System.getProperty("bruno.screenshotDir");
            if (screenshotDir != null) {
                var snapshot = new BufferedImage(editor.getWidth(),editor.getHeight(),BufferedImage.TYPE_INT_RGB);
                var graphics = snapshot.createGraphics(); editor.paint(graphics); graphics.dispose();
                Files.createDirectories(Path.of(screenshotDir));
                ImageIO.write(snapshot,"png",Path.of(screenshotDir,"response-error-layout.png").toFile());
            }
            editor.setSize(1000,com.intellij.util.ui.JBUI.scale(450)); layout(editor);
            var panes = components(editor,JTabbedPane.class).stream().filter(t -> t.indexOfTab("Request") >= 0).findFirst().orElseThrow();
            assertTrue("Short windows use tabs",panes.indexOfTab("Response (Connection error)") >= 0);
            panes.setSelectedIndex(1); layout(editor);
            assertTrue(response.getHeight() > com.intellij.util.ui.JBUI.scale(300));
            assertTrue(message.getHeight() > com.intellij.util.ui.JBUI.scale(60));
        } finally { Disposer.dispose(editor); state.editorProportion=previous; }
    }
    public void testFullToolWindowKeepsErrorVisibleWithLargeFonts() throws Exception {
        var previousLook=UIManager.getLookAndFeel();
        boolean previousDark=!com.intellij.ui.JBColor.isBright();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.darcula.DarculaLaf()); com.intellij.ui.JBColor.setDark(true);
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        double oldProportion=state.editorProportion; state.editorProportion=0.8;
        var draft=state.openEndpoint(endpoint("/api/users","UserService","register"),"Local");
        draft.fields.bodyMode="JSON"; draft.selectedSection="Body"; draft.fields.body="{\"name\":\"example\"}";
        draft.response=new ExecutionResult(0,"Connection error",7,0,"Could not connect to localhost:8080. Check that your application is running and its port matches Base URL in Global Environments.","","",false,false,"");
        var panel=new ApiExplorerPanel(getProject());
        JFrame frame=null;
        try {
            panel.stopScan();
            panel.showDiscovery(new SpringEndpointScanner.Result(List.of(endpoint("/api/users","UserService","register")),List.of()));
            enlargeFonts(panel);
            if (Boolean.getBoolean("bruno.guiTests")) {
                frame=new JFrame("Bruno full tool window response visibility"); frame.setContentPane(panel); frame.setSize(1294,1030); frame.setVisible(true);
            } else { panel.setSize(1294,1030); panel.addNotify(); }
            for (int height : new int[]{1030,850,620}) {
                if (frame != null) { frame.setSize(1294,height); frame.validate(); }
                else panel.setSize(1294,height);
                layout(panel); UIUtil.dispatchAllInvocationEvents();
                if (frame != null) com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                layout(panel);
                assertFalse("Wide tool window must retain explorer",components(panel,JButton.class).stream().filter(b -> "Back to APIs".equals(b.getText())).findFirst().orElseThrow().isVisible());
                var editor=components(panel,RequestEditor.class).getFirst();
                var response=components(editor,ResponseView.class).getFirst();
                var parent=response.getParent();
                if (parent instanceof JTabbedPane panes) { panes.setSelectedComponent(response); layout(panel); }
                var message=components(response,JTextArea.class).getFirst();
                int line=message.getFontMetrics(message.getFont()).getHeight();
                assertTrue("Error text must have readable visible space at height " + height,message.getVisibleRect().height >= line*3);
                var bounds=SwingUtilities.convertRectangle(message,message.getVisibleRect(),panel);
                assertTrue("Error must remain within the tool window",bounds.y >= 0 && bounds.y+bounds.height <= panel.getHeight());
                assertEquals("Automatic sizing must preserve the user's saved split",0.8,state.editorProportion,0.001);
                if (height==1030 && System.getProperty("bruno.screenshotDir")!=null) {
                    var snapshot=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
                    var graphics=snapshot.createGraphics(); panel.paint(graphics); graphics.dispose();
                    ImageIO.write(snapshot,"png",Path.of(System.getProperty("bruno.screenshotDir"),"full-window-error-large-fonts.png").toFile());
                }
            }
        } finally {
            if (frame!=null) frame.dispose(); else panel.removeNotify();
            Disposer.dispose(panel); state.drafts.clear(); state.editorProportion=oldProportion;
            UIManager.setLookAndFeel(previousLook); com.intellij.ui.JBColor.setDark(previousDark);
        }
    }
    private static void enlargeFonts(Container parent) {
        for (var child:parent.getComponents()) {
            if (child.getFont()!=null) child.setFont(child.getFont().deriveFont(child.getFont().getSize2D()*1.4f));
            if (child instanceof Container nested) enlargeFonts(nested);
        }
    }
    public void testFailedSendRevealsResponseWithoutOverwritingSavedSplit() throws Exception {
        var state=getProject().getService(WorkspaceState.class); double previous=state.editorProportion; state.editorProportion=0.8;
        int port; try (var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        var draft=new RequestDraft(); draft.fields.url="http://127.0.0.1:"+port+"/offline";
        var editor=new RequestEditor(getProject(),draft,() -> {},command -> {});
        try {
            resize(editor,1000); editor.send(null);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while (draft.response==null && System.nanoTime()<deadline) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            assertNotNull(draft.response); assertEquals("Connection error",draft.response.outcome());
            layout(editor);
            var splitter=components(editor,com.intellij.ui.JBSplitter.class).getFirst();
            assertTrue("Failure must bring response up",splitter.getProportion() <= 0.5f);
            assertEquals(0.8,state.editorProportion,0.001);
        } finally { Disposer.dispose(editor); state.editorProportion=previous; }
    }
    public void testBodyActionsMatchSelectedBodyMode() {
        var draft = new RequestDraft(); draft.fields.bodyMode="JSON";
        var editor = new RequestEditor(getProject(),draft,() -> {},command -> {});
        try {
            var mode = components(editor,JComboBox.class).stream().filter(c -> c.getItemCount()==5 && "Multipart".equals(c.getItemAt(4))).findFirst().orElseThrow();
            var file = components(editor,JButton.class).stream().filter(b -> "Add file".equals(b.getText())).findFirst().orElseThrow();
            var format = components(editor,JButton.class).stream().filter(b -> "Format JSON".equals(b.getText())).findFirst().orElseThrow();
            assertFalse(file.isVisible()); assertTrue(format.isVisible());
            mode.setSelectedItem("Text"); assertFalse(file.isVisible()); assertFalse(format.isVisible());
            mode.setSelectedItem("Multipart"); assertTrue(file.isVisible()); assertFalse(format.isVisible());
        } finally { Disposer.dispose(editor); }
    }
    public void testNarrowSectionsUseOnlyDropdownAndRemainReachable() {
        var editor = new RequestEditor(getProject(), new RequestDraft(), () -> {}, command -> {});
        try {
            resize(editor, 420);
            var sections = components(editor, BrunoTabbedPane.class).stream().filter(t -> t.indexOfTab("Params") >= 0).findFirst().orElseThrow();
            assertTrue(sections.tabsHidden());
            assertFalse(sections.isFocusable());
            assertEquals(-1, sections.getUI().tabForCoordinate(sections, 10, 10));
            var picker = components(editor, JComboBox.class).stream()
                    .filter(c -> "Request section".equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            picker.setSelectedItem("Advanced");
            assertEquals("Advanced", editor.draft.selectedSection);
        } finally { Disposer.dispose(editor); }
    }
    public void testAuthControlsAreConditionalAndCompact() {
        var editor = new RequestEditor(getProject(), new RequestDraft(), () -> {}, command -> {});
        try {
            resize(editor, 1200);
            var sections = components(editor, JTabbedPane.class).stream().filter(t -> t.indexOfTab("Auth") >= 0).findFirst().orElseThrow();
            sections.setSelectedIndex(sections.indexOfTab("Auth"));
            var mode = components(editor, JComboBox.class).stream().filter(c -> c.getItemCount() == 4 && "None".equals(c.getItemAt(0))).findFirst().orElseThrow();
            assertFalse(components(editor, JLabel.class).stream().anyMatch(l -> "Username".equals(l.getText())));
            mode.setSelectedItem("Basic"); layout(editor);
            assertTrue(components(editor, JLabel.class).stream().anyMatch(l -> "Username".equals(l.getText())));
            assertFalse(components(editor, JLabel.class).stream().anyMatch(l -> "API key name".equals(l.getText())));
            mode.setSelectedItem("API key"); layout(editor);
            assertTrue(components(editor, JLabel.class).stream().anyMatch(l -> "API key name".equals(l.getText())));
            assertFalse(components(editor, JLabel.class).stream().anyMatch(l -> "Username".equals(l.getText())));
            assertFalse(components(editor, JLabel.class).stream().anyMatch(l -> l.getText() != null && l.getText().startsWith("Global environment:")));
        } finally { Disposer.dispose(editor); }
    }
    private static void resize(JComponent panel, int width) {
        panel.setSize(width, 850); panel.dispatchEvent(new ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED)); layout(panel);
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) result.addAll(components(nested, type));
        }
        return result;
    }
    public void testDiscoveryCountsAndCollapsedGroupsSurviveRefresh() {
        var state = getProject().getService(WorkspaceState.class); state.treeInitialized=false; state.expanded.clear(); state.endpointOpened=false;
        var panel = new ApiExplorerPanel(getProject());
        try {
            panel.stopScan();
            var result = new SpringEndpointScanner.Result(List.of(endpoint("/orders","OrderService","orders")),List.of());
            panel.showDiscovery(result);
            var tree = components(panel,JTree.class).getFirst();
            var root = (javax.swing.tree.DefaultMutableTreeNode)tree.getModel().getRoot();
            var module = (javax.swing.tree.DefaultMutableTreeNode)root.getChildAt(0);
            assertEquals("OrderService (1 API)",module.getUserObject().toString());
            assertTrue(components(panel,JLabel.class).stream().anyMatch(l -> "1 API".equals(l.getText())));
            var controller = (javax.swing.tree.DefaultMutableTreeNode)module.getChildAt(0);
            tree.expandPath(new javax.swing.tree.TreePath(controller.getPath()));
            tree.collapsePath(new javax.swing.tree.TreePath(controller.getPath()));
            panel.showDiscovery(result);
            root = (javax.swing.tree.DefaultMutableTreeNode)tree.getModel().getRoot();
            controller = (javax.swing.tree.DefaultMutableTreeNode)root.getChildAt(0).getChildAt(0);
            assertFalse(tree.isExpanded(new javax.swing.tree.TreePath(controller.getPath())));
            panel.openEndpoint(result.endpoints().getFirst());
            assertTrue(state.endpointOpened);
            assertTrue(components(panel,JLabel.class).stream().filter(l -> l.getText()!=null && l.getText().startsWith("Double-click an API")).noneMatch(Component::isVisible));
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testClickingTreeEndpointOpensDraft() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 800);
            panel.stopScan();
            var ep = endpoint("/api/test-click", "TestModule", "testMethod");
            panel.showDiscovery(new SpringEndpointScanner.Result(List.of(ep), List.of()));
            UIUtil.dispatchAllInvocationEvents();
            JTree tree = components(panel, JTree.class).getFirst();
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            UIUtil.dispatchAllInvocationEvents();
            int targetRow = -1;
            for (int r = 0; r < tree.getRowCount(); r++) {
                var path = tree.getPathForRow(r);
                if (path.getLastPathComponent() instanceof javax.swing.tree.DefaultMutableTreeNode node
                        && node.getUserObject() instanceof ApiEndpoint endpoint
                        && endpoint.path().equals("/api/test-click")) {
                    targetRow = r;
                    break;
                }
            }
            assertTrue("Expected endpoint row in tree, total rows: " + tree.getRowCount(), targetRow >= 0);
            Rectangle bounds = tree.getRowBounds(targetRow);
            assertNotNull("Row bounds should not be null", bounds);

            // Single click selects without opening a draft
            tree.setSelectionRow(targetRow);
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, bounds.x + 5, bounds.y + bounds.height / 2, 1, false));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(0, state.drafts.size());

            // Double click opens the draft
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, bounds.x + 5, bounds.y + bounds.height / 2, 2, false));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(1, state.drafts.size());
            assertEquals("/api/test-click", state.drafts.getFirst().sourcePath);
        } finally {
            Disposer.dispose(panel); state.drafts.clear();
        }
    }
    public void testTabClickAndMiddleClickClosesTab() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        state.openEndpoint(endpoint("/api/first", "TestModule", "first"), "Local");
        state.openEndpoint(endpoint("/api/second", "TestModule", "second"), "Local");
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 800);
            UIUtil.dispatchAllInvocationEvents();
            JTabbedPane tabs = components(panel, JTabbedPane.class).stream()
                    .filter(t -> t.getTabCount() == 2).findFirst().orElseThrow();
            assertEquals(1, tabs.getSelectedIndex());
            Component tabComp0 = tabs.getTabComponentAt(0);
            assertNotNull(tabComp0);
            tabComp0.dispatchEvent(new java.awt.event.MouseEvent(tabComp0, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(0, tabs.getSelectedIndex());
            tabComp0.dispatchEvent(new java.awt.event.MouseEvent(tabComp0, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON2));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(1, tabs.getTabCount());
        } finally {
            Disposer.dispose(panel); state.drafts.clear();
        }
    }
    public void testCloseActionClosesActiveDraftWithoutTabsFocus() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        state.openEndpoint(endpoint("/api/close-me", "TestModule", "closeMe"), "Local");
        var panel = new ApiExplorerPanel(getProject());
        try {
            resize(panel, 800);
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(1, state.drafts.stream().filter(d -> d.open).count());
            var dataProvider = com.intellij.ide.DataManager.getDataProvider(panel);
            assertNotNull(dataProvider);
            Runnable closeRunnable = (Runnable) dataProvider.getData(BrunoCloseAction.CLOSE.getName());
            assertNotNull("Close action should be available when an editor is active", closeRunnable);
            closeRunnable.run();
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(0, state.drafts.stream().filter(d -> d.open).count());
        } finally {
            Disposer.dispose(panel); state.drafts.clear();
        }
    }
}
