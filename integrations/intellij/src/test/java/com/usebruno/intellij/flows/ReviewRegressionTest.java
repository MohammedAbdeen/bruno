package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.*;

public class ReviewRegressionTest extends BrunoPlatformTestCase {
    private ExecutionResult response(String body) { return new ExecutionResult(200, "HTTP 200", 1, body.length(), body, "", "", false, false, ""); }
    public void testCloseReleasesHistoryAndPrunesOnlySafeDrafts() {
        var state = new WorkspaceState();
        for (int i = 0; i < 120; i++) {
            var draft = new RequestDraft(); draft.save(); draft.open = false; draft.lastUsed = i; state.drafts.add(draft);
        }
        var pinned = state.drafts.get(0); pinned.pinned = true;
        var dirty = state.drafts.get(1); dirty.fields.body = "unsaved";
        var active = state.drafts.get(2); active.open = true;
        state.prune(); assertEquals(100, state.drafts.size());
        assertTrue(state.drafts.containsAll(List.of(pinned, dirty, active)));
        active.recordResponse(response("sensitive response")); state.close(active);
        assertNull(active.response); assertTrue(active.history.isEmpty());
    }
    public void testHistoryCountByteBudgetAndSerialization() {
        var draft = new RequestDraft();
        for (int i = 0; i < 12; i++) draft.recordResponse(response("private response " + i));
        assertEquals(10, draft.history.size()); assertEquals("private response 11", draft.history.getFirst().body());
        String xml = new org.jdom.output.XMLOutputter().outputString(XmlSerializer.serialize(draft));
        assertFalse(xml.contains("private response"));
        draft.recordResponse(response("x".repeat(3 * 1024 * 1024)));
        assertEquals(1, draft.history.size());
    }
    public void testDirtyComparisonRetainsRevertSemanticsForLargeBodies() {
        var draft = new RequestDraft(); draft.fields.body = "x".repeat(100_000);
        draft.fields.query.add(new RequestRow("tag", "one")); draft.save();
        for (int i = 0; i < 100; i++) assertFalse(draft.dirty());
        draft.fields.body = draft.fields.body + "edited"; assertTrue(draft.dirty());
        draft.revert(); assertFalse(draft.dirty());
        draft.fields.query.getFirst().id = "different-row-id"; assertFalse(draft.dirty());
        draft.fields.query.getFirst().value = "two"; assertTrue(draft.dirty());
    }
    public void testVariableSuggestionsNeverExposeValues() {
        var settings = new ApiSettings(); var draft = new RequestDraft();
        var secret = new RequestRow("token", "never-show-this"); secret.secret = true;
        settings.environments.getFirst().variableRows.add(secret); settings.environments.getFirst().structured = true;
        draft.fields.variables.add(new RequestRow("requestId", "123"));
        assertEquals(List.of("baseUrl", "requestId", "token"), VariableSuggestions.names(settings, draft.fields));
        assertEquals(new VariableSuggestions.Range(2, 9, "tok"), VariableSuggestions.range("x/{{tok}}/suffix", 7));
        assertNull(VariableSuggestions.range("{{token}}/plain", 14));
        assertEquals(new VariableSuggestions.Range(0, 8, "tok"), VariableSuggestions.range("{{tokn}}", 5));
    }
    public void testCapturedCurlUsesResolvedRequestAndRedactsCredentials() throws Exception {
        var fields = new RequestDraft.Fields(); fields.method = "POST"; fields.url = "{{baseUrl}}/items?tag=one&tag=two";
        fields.bodyMode = "JSON"; fields.body = "{\"name\":\"O'Reilly\"}"; fields.auth = "Bearer"; fields.authValue = "private-token";
        var prepared = RequestComposer.prepare(fields, new ApiSettings(), new TransportOptions(15, 30, false, 5, 65536), false);
        assertTrue(prepared.curl().contains("http://localhost:8080/items?tag=one&tag=two"));
        assertTrue(prepared.curl().contains("--data-binary")); assertTrue(prepared.curl().contains("'\"'\"'"));
        assertFalse(prepared.curl().contains("private-token")); assertTrue(prepared.curl().contains("••••"));
    }
    public void testInMemoryVfsDiscoveryIncludesNewFlowImmediately() throws Exception {
        var root = myFixture.getTempDirFixture().findOrCreateDir("vfs-collection");
        var file = FlowFileOperations.create(getProject(), root, "memory");
        assertEquals(1, FlowLocator.findFlows(root).size());
        FlowFileOperations.delete(getProject(), file); assertTrue(FlowLocator.findFlows(root).isEmpty());
    }
    public void testMigrationFailureDoesNotStashPreviousEditorIntoBrokenProfile() throws Exception {
        var settings = ApiSettings.get(getProject());
        var broken = new ApiSettings.Environment(); broken.name = "Broken"; broken.variables = "invalid legacy text";
        settings.environments.add(broken);
        var dialog = new EnvironmentDialog(getProject(), () -> {});
        try {
            var listField = EnvironmentDialog.class.getDeclaredField("list"); listField.setAccessible(true);
            var list = (javax.swing.JList<?>) listField.get(dialog);
            list.setSelectedValue("Broken", true); list.setSelectedValue("Local", true);
            var workingField = EnvironmentDialog.class.getDeclaredField("working"); workingField.setAccessible(true);
            var working = (ApiSettings) workingField.get(dialog);
            var retained = working.environments.get(1);
            assertFalse(retained.structured); assertEquals("invalid legacy text", retained.variables);
        } finally { dialog.dispose(); }
    }
    public void testSharedSecretReferenceSurvivesDeletingDuplicateProfile() {
        var settings = new ApiSettings(); var row = new RequestRow("token", ""); row.secret = true; row.secretRef = "shared-reference";
        settings.environments.getFirst().variableRows.add(row);
        var duplicate = new ApiSettings.Environment(); duplicate.variableRows.add(row.copy()); settings.environments.add(duplicate);
        var before = EnvironmentDialog.secretReferences(settings);
        settings.environments.removeFirst(); before.removeAll(EnvironmentDialog.secretReferences(settings));
        assertTrue(before.isEmpty());
        settings.environments.clear(); assertTrue(EnvironmentDialog.secretReferences(settings).isEmpty());
    }
    public void testCurlRedactsBeforeShellQuoting() {
        String secret = "has'quote";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost/test"))
                .header("X-Token", secret).POST(java.net.http.HttpRequest.BodyPublishers.ofString(secret)).build();
        String curl = CurlCommands.create(request, secret, List.of(), List.of(secret));
        assertFalse(curl.contains("quote")); assertFalse(curl.contains("has")); assertTrue(curl.contains("••••"));
    }
    public void testEnvironmentReloadDisablesSendWhileEditorIsIdle() throws Exception {
        var editor = new RequestEditor(getProject(), new RequestDraft(), () -> {}, command -> {});
        try {
            var sendField = RequestEditor.class.getDeclaredField("send"); sendField.setAccessible(true);
            var send = (javax.swing.JButton) sendField.get(editor);
            var settings = ApiSettings.get(getProject()); settings.environments.clear(); settings.changed();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (send.isEnabled() && System.nanoTime() < deadline) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
            }
            assertFalse("Send must reflect deleted environments without typing", send.isEnabled());
        } finally { com.intellij.openapi.util.Disposer.dispose(editor); }
    }

    public void testScriptSessionOverridesKeepDeclaredSecretMasking() throws Exception {
        var settings=new ApiSettings(); var env=settings.environments.getFirst(); env.migrate();
        var secret=new RequestRow("credential", ""); secret.secret=true; secret.secretRef="not-read-when-session-overrides"; env.variableRows.add(secret);
        settings.session("Local").values.put("credential","replacement-value");
        var fields=new RequestDraft.Fields(); fields.url="{{baseUrl}}/?credential={{credential}}";
        assertEquals("••••",RequestComposer.variables(settings,fields,false).get("credential"));
        var prepared=RequestComposer.prepare(fields,settings,new TransportOptions(15,30,false,5,65536),true);
        assertTrue(prepared.request().uri().toString().contains("replacement-value")); assertFalse(prepared.curl().contains("replacement-value"));
    }

}
