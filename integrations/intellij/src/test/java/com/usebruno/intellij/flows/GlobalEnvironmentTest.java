package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.xmlb.XmlSerializer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class GlobalEnvironmentTest extends BrunoPlatformTestCase {
    public void testProfilesAreSharedAcrossProjectsAndSurviveSerialization() {
        var settings = ApiSettings.get(getProject());
        var staging = new ApiSettings.Environment(); staging.name = "Staging"; staging.baseUrl = "https://staging.example.test";
        settings.environments.add(staging); settings.selected = staging.name; settings.changed();
        assertSame(settings, ApiSettings.get(ProjectManager.getInstance().getDefaultProject()));
        var global = ApplicationManager.getApplication().getService(GlobalApiSettings.class);
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(global), GlobalApiSettings.class);
        assertEquals("Staging", restored.settings.selected);
        assertEquals("https://staging.example.test", restored.settings.environments.get(1).baseUrl);
        assertFalse(restored.migratedProjects.isEmpty());
    }
    public void testMigrationPreservesConflictsSecretsAndScopedDefaults() {
        var global = new GlobalApiSettings(); global.settings.environments.getFirst().baseUrl = "https://existing.test";
        var legacy = new ApiSettings(); legacy.environments.getFirst().baseUrl = "https://incoming.test";
        var secret = new RequestRow("token", ""); secret.secret = true; secret.secretRef = "legacy-secret-reference";
        legacy.defaults.add(secret);
        global.merge(legacy, "TradingEngine");
        assertEquals("https://existing.test", global.settings.environments.getFirst().baseUrl);
        var imported = global.settings.environments.get(1);
        assertEquals("Local (TradingEngine)", imported.name);
        assertEquals("legacy-secret-reference", imported.variableRows.getFirst().secretRef);
        assertTrue(global.settings.defaults.isEmpty());
    }
    public void testGlobalSelectionUpdatesEveryOpenRequest() {
        var settings = ApiSettings.get(getProject());
        var staging = new ApiSettings.Environment(); staging.name = "Staging"; settings.environments.add(staging);
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var a = new RequestDraft(); a.save(); var b = new RequestDraft(); b.save(); state.drafts.addAll(List.of(a,b));
        var panel = new ApiExplorerPanel(getProject());
        try {
            settings.selected = "Staging"; settings.changed(); UIUtil.dispatchAllInvocationEvents();
            assertEquals("Staging", a.fields.environment); assertEquals("Staging", b.fields.environment);
            assertEquals(1, components(panel, JComboBox.class).stream().filter(c -> "Shared by all API requests and projects in this IDE".equals(c.getToolTipText())).count());
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testTransportErrorIsWrappedAndJsonControlsAreHidden() throws Exception {
        var response = new ResponseView(getProject());
        try {
            response.show(new ExecutionResult(0, "Connection error", 1, 0, "Start the application, or check Base URL in Global Environments.", "", "", false, false, ""));
            var area = components(response, JTextArea.class).getFirst();
            assertTrue(area.getLineWrap()); assertTrue(area.getWrapStyleWord());
            var pretty = components(response, JCheckBox.class).getFirst(); assertFalse(pretty.isVisible());
            var renderer = components(response,JComboBox.class).stream().filter(c -> "Response viewer".equals(c.getToolTipText())).findFirst().orElseThrow();
            assertFalse(renderer.isVisible());
            response.show(new ExecutionResult(200, "HTTP 200", 1, 2, "{}", "content-type: application/json", "", false, false, ""));
            assertTrue(pretty.isVisible()); assertTrue(renderer.isVisible());
        } finally { response.disposeView(); }
    }
    public void testGutterOpeningUsesRouteAndReusesItsDraft() {
        var state = getProject().getService(WorkspaceState.class); state.drafts.clear();
        var endpoint = new ApiEndpoint("POST", "/api/v1/users", "demo.Users", "register", "file:///Users.java", 10,
                "Users", "file:///Users.java", "register()", 0);
        var panel = new ApiExplorerPanel(getProject());
        try {
            panel.openEndpoint(endpoint); panel.openEndpoint(endpoint);
            assertEquals(1, state.drafts.size());
            assertEquals("POST", state.drafts.getFirst().fields.method);
            assertEquals("{{baseUrl}}/api/v1/users", state.drafts.getFirst().fields.url);
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testSavingGlobalProfilesPreservesScriptSessions() throws Exception {
        var settings=ApiSettings.get(getProject());
        var saved=new java.util.concurrent.atomic.AtomicBoolean(); var dialog=new EnvironmentDialog(getProject(),() -> saved.set(true));
        try {
            long metadata=settings.profilesRevision();
            settings.session("Local").values.put("token","session-token"); settings.sessionChanged();
            assertEquals(metadata,settings.profilesRevision());
            dialog.doOKAction();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!saved.get() && System.nanoTime()<deadline) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            assertTrue(saved.get()); assertEquals("session-token",ApiSettings.get(getProject()).session("Local").values.get("token"));
        } finally { dialog.dispose(); }
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(components(nested, type));
        }
        return found;
    }
}
