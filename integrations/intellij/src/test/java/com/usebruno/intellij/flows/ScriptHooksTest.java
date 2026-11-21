package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.*;

public class ScriptHooksTest extends BrunoPlatformTestCase {
    private String previous;
    @Override protected void setUp() throws Exception {
        super.setUp(); var preferences = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        previous = preferences.jsRuntimePath; preferences.jsRuntimePath = Path.of("../..", "packages/bruno-js").toAbsolutePath().normalize().toString();
    }
    @Override protected void tearDown() throws Exception {
        try { ApplicationManager.getApplication().getService(BrunoPreferences.class).jsRuntimePath=previous; } finally { super.tearDown(); }
    }
    public void testPreRequestUsesBrunoQuickJsAndFeedsTheResolvedRequest() throws Exception {
        var fields = new RequestDraft.Fields(); fields.url="{{baseUrl}}/items/{{id}}";
        fields.preScript="bru.setVar('id', '42'); req.setHeader('X-From-Script', 'yes');";
        var settings = new ApiSettings();
        var prepared = ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536));
        assertEquals("http://localhost:8080/items/42",prepared.request().uri().toString());
        assertEquals("yes",prepared.request().headers().firstValue("X-From-Script").orElseThrow());
    }
    public void testPostResponseUpdatesSessionVariablesWithoutPersistingToken() throws Exception {
        var fields = new RequestDraft.Fields(); fields.postScript="bru.setVar('token', res.getBody().token);";
        var settings = new ApiSettings();
        var response = new ExecutionResult(200,"HTTP 200",1,17,"{\"token\":\"sample-secret\"}","content-type: application/json","",false,false,"");
        var processed = ScriptRequest.post(getProject(),fields,settings,response);
        assertEquals(200,processed.status()); assertEquals("sample-secret",settings.session("Local").values.get("token"));
        assertFalse(new org.jdom.output.XMLOutputter().outputString(com.intellij.util.xmlb.XmlSerializer.serialize(settings)).contains("sample-secret"));
        assertEquals("••••",RequestComposer.variables(settings,fields,false).get("token"));
    }
    public void testUnusedMissingSecretDoesNotBlockPreRequestScript() throws Exception {
        var fields = new RequestDraft.Fields(); fields.preScript = "bru.setVar('id','42');";
        var settings = new ApiSettings(); settings.environments.getFirst().migrate();
        var secret = new RequestRow("unused", ""); secret.secret=true; secret.secretRef="not-a-real-credential"; settings.environments.getFirst().variableRows.add(secret);
        var prepared = ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536));
        assertEquals("http://localhost:8080/",prepared.request().uri().toString());
    }
    public void testWorkerReusesProcessAndIsolatesHookScopes() throws Exception {
        var fields = new RequestDraft.Fields(); var settings = new ApiSettings();
        var worker = getProject().getService(ScriptWorker.class);
        fields.preScript = "globalThis.onlyThisHook = 42; bru.setVar('first', 'yes');";
        ScriptRequest.prepare(getProject(), fields, settings, new TransportOptions(15,30,false,5,65536));
        long pid = worker.processId(); assertTrue(pid > 0);
        fields.postScript = "if (typeof onlyThisHook !== 'undefined') throw Error('scope leaked'); bru.setVar('second', 'yes');";
        ScriptRequest.post(getProject(),fields,settings,new ExecutionResult(200,"HTTP 200",1,2,"{}","","",false,false,""));
        assertEquals(pid,worker.processId());
        assertEquals("yes", settings.session("Local").values.get("second"));
        fields.preScript = "throw Error('expected script error');";
        try { ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("expected script error")); }
        fields.preScript = "bru.setVar('afterError', 'yes');";
        ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536));
        assertEquals(pid,worker.processId());
        assertEquals("yes",settings.session("Local").values.get("afterError"));
    }
    public void testWorkerPreservesTerminalResultWhenProcessExits() throws Exception {
        if (com.intellij.openapi.util.SystemInfo.isWindows) return;
        var executable=Files.createTempFile("bruno-worker-eof-",".js");
        var worker=new ScriptWorker(getProject());
        try {
            Files.writeString(executable,"#!/usr/bin/env node\nprocess.stdout.write('{\"kind\":\"ready\"}\\n'); process.stdin.once('data',()=>{process.stdout.write('{\"kind\":\"result\"}\\n',()=>process.exit(0));});\n");
            assertTrue(executable.toFile().setExecutable(true));
            try (var lease=worker.acquire(executable.toString(),Path.of(previous.isBlank() ? "." : previous).toAbsolutePath())) {
                var process=ProcessHandle.of(worker.processId()).orElseThrow();
                lease.send(new com.google.gson.JsonObject()); process.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
                // Give the pipe reader time to observe EOF after the result.
                Thread.sleep(100);
                assertEquals("{\"kind\":\"result\"}",lease.read()); lease.complete();
            }
        } finally { worker.dispose(); Files.deleteIfExists(executable); }
    }
    public void testProjectWorkerDisposalTerminatesProcess() throws Exception {
        var worker = new ScriptWorker(getProject());
        try {
            var preferences = ApplicationManager.getApplication().getService(BrunoPreferences.class);
            try (var lease = worker.acquire(preferences.nodeExecutable,Path.of(preferences.jsRuntimePath))) { lease.complete(); }
            long pid = worker.processId();
            worker.dispose();
            var process = ProcessHandle.of(pid);
            if (process.isPresent()) process.get().onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(process.isEmpty() || !process.get().isAlive());
        } finally { worker.dispose(); }
    }
    public void testCancelledQueuedHookDoesNotKillActiveWorker() throws Exception {
        var worker = new ScriptWorker(getProject());
        try {
            var preferences = ApplicationManager.getApplication().getService(BrunoPreferences.class);
            try (var lease = worker.acquire(preferences.nodeExecutable,Path.of(preferences.jsRuntimePath))) {
                long pid = worker.processId();
                var entered = new java.util.concurrent.CountDownLatch(1);
                var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
                var waiter = new Thread(() -> {
                    entered.countDown();
                    try (var ignored = worker.acquire(preferences.nodeExecutable,Path.of(preferences.jsRuntimePath))) { }
                    catch (InterruptedException expected) { interrupted.set(true); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                waiter.start(); assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                waiter.interrupt(); waiter.join(2000);
                assertFalse(waiter.isAlive()); assertTrue(interrupted.get());
                assertEquals(pid,worker.processId()); assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
                lease.complete();
            }
        } finally { worker.dispose(); }
    }
    public void testScriptUrlChangePreservesQueryMetadataAndDisabledRows() throws Exception {
        var fields = new RequestDraft.Fields(); var settings = new ApiSettings();
        fields.url = "{{baseUrl}}/before?tag=one&tag=two";
        var first = new RequestRow("tag","one"); first.description = "First tag";
        var second = new RequestRow("tag","two"); second.description = "Second tag";
        var disabled = new RequestRow("debug","true"); disabled.enabled = false; disabled.description = "Keep disabled";
        fields.query.addAll(java.util.List.of(first,second,disabled));
        fields.preScript = "req.setUrl('{{baseUrl}}/after?tag=changed&tag=two&debug=false');";
        var prepared = ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536));
        assertEquals("http://localhost:8080/after?tag=changed&tag=two",prepared.request().uri().toString());
        assertEquals(first.id,fields.query.get(0).id); assertEquals("First tag",fields.query.get(0).description);
        assertEquals(second.id,fields.query.get(1).id); assertEquals("Second tag",fields.query.get(1).description);
        assertEquals(disabled.id,fields.query.get(2).id); assertFalse(fields.query.get(2).enabled);
        assertEquals("Keep disabled",fields.query.get(2).description);
    }
    public void testDeadlineKillsInfiniteScriptWorker() throws Exception {
        var fields = new RequestDraft.Fields(); fields.preScript="while (true) {}";
        var outcome = new java.util.concurrent.atomic.AtomicReference<ExecutionResult>();
        var call = new RequestExecution(outcome::set,null);
        call.start(() -> ScriptRequest.prepare(getProject(),fields,new ApiSettings(),new TransportOptions(1,1,false,5,65536)),1);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (outcome.get()==null && System.nanoTime()<deadline) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10); }
        assertNotNull(outcome.get()); assertEquals("Timeout",outcome.get().outcome());
        var worker = getProject().getService(ScriptWorker.class);
        long resetDeadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (worker.processId() != -1 && System.nanoTime()<resetDeadline) Thread.sleep(10);
        assertEquals(-1L,worker.processId());
        fields.preScript = "bru.setVar('recovered','yes');";
        var settings = new ApiSettings();
        ScriptRequest.prepare(getProject(),fields,settings,new TransportOptions(15,30,false,5,65536));
        assertEquals("yes",settings.session("Local").values.get("recovered"));
    }

}
