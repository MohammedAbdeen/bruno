package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.openapi.application.ReadAction;

public class DiscoveryCacheTest extends LightJavaCodeInsightFixtureTestCase {
    public void testCacheInvalidatesWhenSpringControllersAreAdded() {
        var cache = getProject().getService(EndpointDiscovery.class);
        var first = ReadAction.compute(cache::scan);
        assertTrue(first.warnings().getFirst().contains("Spring Web annotations were not found"));
        assertSame(first, ReadAction.compute(cache::scan));
        myFixture.addFileToProject("org/springframework/web/bind/annotation/RequestMapping.java", "package org.springframework.web.bind.annotation; public @interface RequestMapping { String value(); }");
        myFixture.addFileToProject("org/springframework/web/bind/annotation/RestController.java", "package org.springframework.web.bind.annotation; public @interface RestController { }");
        myFixture.addFileToProject("demo/Users.java", "package demo; import org.springframework.web.bind.annotation.*; @RestController class Users { @RequestMapping(\"/users\") void users(){} }");
        var second = ReadAction.compute(cache::scan);
        assertNotSame(first, second); assertEquals(1, second.endpoints().size());
        assertSame(second, ReadAction.compute(cache::scan));
    }
    public void testQueuedDiscoveryDoesNotScanAfterToolWindowIsHidden() throws Exception {
        var visible=new java.util.concurrent.atomic.AtomicBoolean(true);
        var panel=new ApiExplorerPanel(getProject(),visible::get);
        try {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
            var field=ApiExplorerPanel.class.getDeclaredField("discoveryTimer"); field.setAccessible(true);
            var timer=(javax.swing.Timer)field.get(panel);
            assertTrue(timer.isRunning());
            visible.set(false);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750);
            while (System.nanoTime()<until) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            var scanned=ApiExplorerPanel.class.getDeclaredField("hasScanned"); scanned.setAccessible(true);
            assertFalse("A queued timer must recheck visibility",scanned.getBoolean(panel));
            visible.set(true); panel.scheduleDiscovery();
            until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while (!scanned.getBoolean(panel) && System.nanoTime()<until) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
            assertTrue("Deferred discovery must resume when visible",scanned.getBoolean(panel));
        } finally { com.intellij.openapi.util.Disposer.dispose(panel); }
    }
    public void testControllerAdditionAutomaticallyRefreshesExplorer() throws Exception {
        var panel = new ApiExplorerPanel(getProject(),() -> true);
        try {
            panel.stopScan();
            myFixture.addFileToProject("org/springframework/web/bind/annotation/RequestMapping.java", "package org.springframework.web.bind.annotation; public @interface RequestMapping { String value(); }");
            myFixture.addFileToProject("org/springframework/web/bind/annotation/RestController.java", "package org.springframework.web.bind.annotation; public @interface RestController { }");
            myFixture.addFileToProject("demo/NewApi.java", "package demo; import org.springframework.web.bind.annotation.*; @RestController class NewApi { @RequestMapping(\"/new-api\") void run(){} }");
            var endpoints = ApiExplorerPanel.class.getDeclaredField("endpoints"); endpoints.setAccessible(true);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while (((java.util.List<?>) endpoints.get(panel)).isEmpty() && System.nanoTime() < deadline) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
            }
            assertFalse("Adding a controller should refresh without clicking Refresh", ((java.util.List<?>) endpoints.get(panel)).isEmpty());
        } finally { com.intellij.openapi.util.Disposer.dispose(panel); }
    }

}
