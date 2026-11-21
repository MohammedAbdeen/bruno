package com.usebruno.intellij.flows;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ApiRequestTest {
    @Test public void expandsVariablesAndPreservesEqualsInHeaders() {
        var values = RequestTemplates.pairs("token=abc==\nversion=v1\nprefix=/{{version}}\n# comment\n");
        assertEquals("Bearer abc==", RequestTemplates.expand("Bearer {{token}}", values));
        assertEquals("/v1/users", RequestTemplates.expand("{{prefix}}/users", values));
        assertThrows(IllegalArgumentException.class, () -> RequestTemplates.expand("{{missing}}", values));
        assertThrows(IllegalArgumentException.class, () -> RequestTemplates.expand("{{a}}", Map.of("a", "{{b}}", "b", "{{a}}")));
        assertThrows(IllegalArgumentException.class, () -> RequestTemplates.pairs("token=x\ntoken=y"));
        assertThrows(IllegalArgumentException.class, () -> RequestTemplates.pairs("invalid row"));
    }

    @Test(timeout=3000) public void rejectsExponentialExpansionBeforeLargeAllocations() {
        var values=new HashMap<String,String>(); values.put("v0","x");
        for (int i=1;i<=40;i++) values.put("v"+i,"{{v"+(i-1)+"}}{{v"+(i-1)+"}}");
        var error=assertThrows(IllegalArgumentException.class,() -> RequestTemplates.expand("{{v40}}",values));
        assertTrue(error.getMessage().contains("1 MiB"));
    }
    @Test public void memoizesRepeatedVariablesAndPreservesLiteralLargeBodies() {
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var values=new HashMap<String,String>() {
            @Override public String get(Object key) { reads.incrementAndGet(); return super.get(key); }
        };
        values.put("v0","x");
        for (int i=1;i<=16;i++) values.put("v"+i,"{{v"+(i-1)+"}}{{v"+(i-1)+"}}");
        assertEquals(65536,RequestTemplates.expand("{{v16}}",values).length());
        assertTrue("Repeated variable references must reuse resolved values",reads.get()<40);
        String raw="x".repeat(RequestTemplates.MAX_EXPANDED_CHARS+1);
        assertSame(raw,RequestTemplates.expand(raw,Map.of()));
        assertThrows(IllegalArgumentException.class,() -> RequestTemplates.expand("{{value}}",Map.of("value",raw)));
        assertEquals("$1\\literal",RequestTemplates.expand("{{value}}",Map.of("value","$1\\literal")));
    }

    @Test public void queryCodecMergePreservesDescriptionsAndDisabledRows() {
        var existing = new ArrayList<>(List.of(
                new RequestRow("q", "oldSearch"),
                new RequestRow("debug", "true")
        ));
        existing.get(0).description = "Search term";
        existing.get(0).enabled = true;
        existing.get(1).description = "Verbose mode";
        existing.get(1).enabled = false;

        // URL edited to ?q=newSearch&page=2
        var parsed = List.of(
                new RequestRow("q", "newSearch"),
                new RequestRow("page", "2")
        );

        var merged = QueryCodec.merge(existing, parsed);
        assertEquals(3, merged.size());

        // First row: q should have updated value, enabled = true, and preserved description
        var row0 = merged.get(0);
        assertEquals("q", row0.key);
        assertEquals("newSearch", row0.value);
        assertEquals("Search term", row0.description);
        assertTrue(row0.enabled);

        // Second row: page is newly added
        var row1 = merged.get(1);
        assertEquals("page", row1.key);
        assertEquals("2", row1.value);
        assertTrue(row1.enabled);

        // Third row: disabled debug parameter is preserved
        var row2 = merged.get(2);
        assertEquals("debug", row2.key);
        assertEquals("true", row2.value);
        assertEquals("Verbose mode", row2.description);
        assertFalse(row2.enabled);
    }
}
