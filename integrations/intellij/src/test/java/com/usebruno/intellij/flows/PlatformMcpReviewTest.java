package com.usebruno.intellij.flows;

import com.intellij.ide.DataManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.UIUtil;
import javax.swing.*;
import java.util.*;

public class PlatformMcpReviewTest extends BrunoPlatformTestCase {
    public void testStandaloneEditorUpdatesOnEnvironmentEventsAndDisconnectsOnDispose() {
        var settings=ApiSettings.get(getProject());
        String previous=settings.selected;
        var alternative=new ApiSettings.Environment(); alternative.name="Event-driven test";
        settings.environments.add(alternative);
        var draft=new RequestDraft();
        var editor=new RequestEditor(getProject(),draft,() -> {},ignored -> {});
        try {
            settings.selected=alternative.name; settings.changed(); UIUtil.dispatchAllInvocationEvents();
            assertEquals("Environment events must update even standalone editors",alternative.name,draft.fields.environment);
            Disposer.dispose(editor);
            settings.selected=previous; settings.changed(); UIUtil.dispatchAllInvocationEvents();
            assertEquals("Disposed editor must not react to application events",alternative.name,draft.fields.environment);
        } finally {
            Disposer.dispose(editor); settings.selected=previous; settings.environments.remove(alternative); settings.changed();
            UIUtil.dispatchAllInvocationEvents();
        }
    }
    public void testSessionEventsDoNotRebuildTheEnvironmentSelector() throws Exception {
        var settings=ApiSettings.get(getProject());
        var panel=new ApiExplorerPanel(getProject()); panel.stopScan();
        var alternative=new ApiSettings.Environment(); alternative.name="Metadata update test";
        try {
            UIUtil.dispatchAllInvocationEvents();
            var field=ApiExplorerPanel.class.getDeclaredField("globalEnvironment"); field.setAccessible(true);
            var selector=(JComboBox<?>)field.get(panel);
            var changes=new java.util.concurrent.atomic.AtomicInteger();
            selector.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
                public void intervalAdded(javax.swing.event.ListDataEvent event) { changes.incrementAndGet(); }
                public void intervalRemoved(javax.swing.event.ListDataEvent event) { changes.incrementAndGet(); }
                public void contentsChanged(javax.swing.event.ListDataEvent event) { changes.incrementAndGet(); }
            });
            for (int i=0;i<20;i++) settings.sessionChanged();
            UIUtil.dispatchAllInvocationEvents();
            assertEquals("Runtime variable updates must not disturb the profile selector",0,changes.get());
            settings.environments.add(alternative); settings.changed(); UIUtil.dispatchAllInvocationEvents();
            assertTrue(changes.get()>0);
            assertTrue(java.util.stream.IntStream.range(0,selector.getItemCount()).anyMatch(index -> alternative.name.equals(selector.getItemAt(index))));
        } finally {
            Disposer.dispose(panel); settings.environments.remove(alternative); settings.changed(); UIUtil.dispatchAllInvocationEvents();
        }
    }
    public void testCachedCloseActionKeepsItsOriginalTabAfterSelectionChanges() throws Exception {
        var state=getProject().getService(WorkspaceState.class); state.drafts.clear();
        var first=new RequestDraft(); first.name="first"; first.save();
        var second=new RequestDraft(); second.name="second"; second.save();
        state.drafts.addAll(List.of(first,second)); state.activeId=first.id;
        var panel=new ApiExplorerPanel(getProject()); panel.stopScan();
        try {
            var provider=DataManager.getDataProvider(panel); assertNotNull(provider);
            var action=(Runnable)provider.getData(BrunoCloseAction.CLOSE.getName()); assertNotNull(action);
            var field=ApiExplorerPanel.class.getDeclaredField("tabs"); field.setAccessible(true);
            var tabs=(JTabbedPane)field.get(panel); tabs.setSelectedIndex(1);
            action.run();
            assertFalse(first.open); assertTrue("Selection change must not retarget a cached Close action",second.open);
            action.run(); assertTrue("Repeated stale actions must be harmless",second.open);
            Disposer.dispose(panel);
            assertNull("Disposed tool window must release its data provider",DataManager.getDataProvider(panel));
            assertNull(provider.getData(BrunoCloseAction.CLOSE.getName()));
        } finally { Disposer.dispose(panel); state.drafts.clear(); }
    }
    public void testFlowHistoryHasDisposerOwnershipAndReleasesItsChildren() throws Exception {
        var panel=new BrunoFlowsPanel(getProject());
        var field=BrunoFlowsPanel.class.getDeclaredField("runView"); field.setAccessible(true);
        var view=(FlowRunView)field.get(panel);
        var releases=new java.util.concurrent.atomic.AtomicInteger();
        Disposer.register(view,releases::incrementAndGet);
        try {
            Disposer.dispose(panel);
            assertTrue(Disposer.isDisposed(view)); assertEquals(1,releases.get());
            Disposer.dispose(panel); assertEquals(1,releases.get());
        } finally { Disposer.dispose(panel); }
    }
}
