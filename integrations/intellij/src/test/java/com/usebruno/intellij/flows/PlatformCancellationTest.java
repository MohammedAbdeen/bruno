package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.*;
import java.util.*;

public class PlatformCancellationTest extends LightJavaCodeInsightFixtureTestCase {
    public void testCachedDiscoveryStillHonorsCancellationAndKeepsItsCache() {
        var cache=getProject().getService(EndpointDiscovery.class);
        var previous=ReadAction.compute(cache::scan);
        var indicator=new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(() -> { indicator.cancel(); ReadAction.run(cache::scan); },indicator);
            fail("Cancelled discovery must not publish cached results");
        } catch (ProcessCanceledException expected) { }
        assertSame(previous,ReadAction.compute(cache::scan));
    }
    public void testOpenApiExportCanCancelEvenWhenRoutesNeedNoPsiResolution() {
        var indicator=new EmptyProgressIndicator();
        var examined=new java.util.concurrent.atomic.AtomicInteger();
        List<ApiEndpoint> endpoints=new AbstractList<>() {
            @Override public int size() { return 10000; }
            @Override public ApiEndpoint get(int index) {
                examined.incrementAndGet(); indicator.cancel();
                return new ApiEndpoint("GET","unresolved-route","demo.Users","list","",0,"module","","list()",0);
            }
        };
        try {
            ProgressManager.getInstance().runProcess(() -> ReadAction.run(() -> OpenApiExport.create(getProject(),endpoints,"http://localhost:8080")),indicator);
            fail("Export must honor cancellation before serializing skipped routes");
        } catch (ProcessCanceledException expected) { }
        assertEquals(1,examined.get());
    }
}
