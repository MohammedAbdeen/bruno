package com.usebruno.intellij.flows;

import org.junit.Test;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.*;
import static org.junit.Assert.*;

public class WorkspaceModelTest {
    private ApiEndpoint endpoint(String path, String signature, int variant) {
        return new ApiEndpoint("GET", path, "demo.Users", "get", "file:///Users.java", 10,
                "service", "file:///Users.java", signature, variant);
    }
    @Test public void identityIsIndependentOfEditedUrlAndDistinctForOverloads() {
        WorkspaceState state = new WorkspaceState();
        var first = state.openEndpoint(endpoint("/users/{id}", "get(long)", 0), "Local");
        first.fields.url = "https://example.test/custom";
        assertSame(first, state.openEndpoint(endpoint("/changed", "get(long)", 0), "Local"));
        assertNotEquals(first.id, state.openEndpoint(endpoint("/users/{id}", "get(String)", 0), "Local").id);
        assertNotEquals(first.id, state.openEndpoint(endpoint("/users/{id}", "get(long)", 1), "Local").id);
    }
    @Test public void duplicationAndClosePreserveIndependentDirtyDrafts() {
        WorkspaceState state = new WorkspaceState();
        var first = state.openEndpoint(endpoint("/users", "get()", 0), "Local");
        assertFalse(first.dirty());
        first.fields.body = "original"; assertTrue(first.dirty());
        var second = first.duplicate(); second.fields.body = "other";
        assertEquals("original", first.fields.body); assertNotEquals(first.id, second.id);
        first.open = false;
        assertSame(first, state.openEndpoint(endpoint("/users", "get()", 0), "Local"));
        assertTrue(first.dirty()); first.save(); assertFalse(first.dirty());
        first.fields.body = "changed"; first.revert(); assertEquals("original", first.fields.body);
    }
    @Test public void rowIdentityDoesNotCreateFalseDirtyChanges() {
        RequestDraft draft = new RequestDraft(); draft.fields.query.add(new RequestRow("a", "1")); draft.save();
        draft.fields.query.set(0, new RequestRow("a", "1"));
        assertFalse(draft.dirty());
        draft.fields.query.getFirst().enabled = false; assertTrue(draft.dirty());
    }
    @Test public void draftRoundTripOmitsSessionResponses() {
        WorkspaceState state = new WorkspaceState();
        var draft = state.openEndpoint(endpoint("/users", "get()", 0), "Local");
        draft.fields.headers.add(new RequestRow("Authorization", "Bearer {{token}}"));
        draft.response = new ExecutionResult(200, "OK", 1, 6, "private-response", "", "", false, false, "");
        var xml = XmlSerializer.serialize(state);
        var restored = XmlSerializer.deserialize(xml, WorkspaceState.class);
        assertEquals(1, restored.drafts.size()); assertTrue(restored.drafts.getFirst().dirty());
        assertNull(restored.drafts.getFirst().response);
        assertEquals("Bearer {{token}}", restored.drafts.getFirst().fields.headers.getFirst().value);
    }
    @Test public void bulkPreservesRepeatsDisabledRowsDescriptionsAndEscapes() {
        var first = new RequestRow("tag", "a=b\nline\\two"); first.description = "description\tmore";
        var second = new RequestRow("tag", ""); second.enabled = false;
        var rows = List.of(first, second);
        var parsed = BulkRows.parse(BulkRows.format(rows), rows, false);
        assertEquals(2, parsed.size()); assertEquals(first.id, parsed.getFirst().id);
        assertEquals(first.value, parsed.getFirst().value); assertEquals(first.description, parsed.getFirst().description);
        assertFalse(parsed.get(1).enabled); assertEquals("", parsed.get(1).value);
        assertThrows(IllegalArgumentException.class, () -> BulkRows.parse("a=1\na=2", List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> BulkRows.parse("bad\\q=x", List.of(), false));
    }
    @Test public void bulkSecretReferencesNeverExposeSecretValues() {
        var secret = new RequestRow("token", ""); secret.secret = true; secret.secretRef = "Bruno/project/key";
        String bulk = BulkRows.format(List.of(secret));
        var parsed = BulkRows.parse(bulk, List.of(secret), true);
        assertTrue(parsed.getFirst().secret); assertEquals(secret.secretRef, parsed.getFirst().secretRef);
        assertEquals("", parsed.getFirst().value);
        assertThrows(IllegalArgumentException.class, () -> BulkRows.parse("token={{secret:unknown}}", List.of(secret), true));
    }
    @Test public void queryRoundTripPreservesUnicodeDuplicatesAndEncoding() {
        String url = "https://example.test/a?tag=a%2Fb&tag=%E2%9C%93&empty=&q={{query}}";
        var split = QueryCodec.split(url);
        assertEquals(4, split.rows().size()); assertEquals("a/b", split.rows().getFirst().value);
        assertEquals(url, QueryCodec.join(split.base(), split.rows(), split.fragment()));
    }
}
