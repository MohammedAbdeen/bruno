package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class ExecutionTest extends BrunoPlatformTestCase {
    private HttpServer server;
    private ExecutorService executor;
    @Override protected void setUp() throws Exception {
        super.setUp();
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.start();
    }
    @Override protected void tearDown() throws Exception {
        try { server.stop(0); executor.shutdownNow(); } finally { super.tearDown(); }
    }
    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private RequestExecution run(String path, TransportOptions options, AtomicReference<ExecutionResult> result) {
        var fields = new RequestDraft.Fields(); fields.url = url(path);
        var call = new RequestExecution(result::set, null);
        call.start(() -> RequestComposer.prepare(fields, new ApiSettings(), options, true), options.totalSeconds());
        return call;
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(10);
        }
        assertTrue("Timed out waiting for async request", condition.getAsBoolean());
    }
    public void testResponseCharsetAndSnapshot() throws Exception {
        server.createContext("/latin", exchange -> {
            byte[] body = "café".getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        run("/latin", new TransportOptions(2, 5, false, 5, 65536), result);
        waitFor(() -> result.get() != null);
        assertEquals("café", result.get().body()); assertEquals(200, result.get().status());
        assertTrue(result.get().details().contains("ISO-8859-1"));
    }
    public void testCsrfLoginReusesSessionCookieAcrossRequestsAndHonorsExplicitCookies() throws Exception {
        var store=getProject().getService(ApiCookies.class);
        var received=new AtomicReference<String>();
        server.createContext("/csrf",exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie","SESSION=session-one; Path=/; HttpOnly; SameSite=Lax");
            byte[] token="{\"data\":{\"token\":\"csrf-one\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,token.length); exchange.getResponseBody().write(token); exchange.close();
        });
        server.createContext("/login",exchange -> {
            String cookie=exchange.getRequestHeaders().getFirst("Cookie"); received.set(cookie);
            boolean valid=cookie!=null && cookie.contains("SESSION=session-one") && "csrf-one".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
            byte[] body=(valid ? "logged in" : "CSRF_INVALID").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 403,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var options=new TransportOptions(2,5,false,5,65536);
        var settings=new ApiSettings();
        var first=new RequestDraft.Fields(); first.url=url("/csrf");
        var tokenResult=new AtomicReference<ExecutionResult>();
        var tokenCall=new RequestExecution(tokenResult::set,null); tokenCall.cookies(uri -> store.jar("csrf-test",uri));
        tokenCall.start(() -> RequestComposer.prepare(first,settings,options,true),5); waitFor(() -> tokenResult.get()!=null);
        assertEquals(200,tokenResult.get().status());
        var login=new RequestDraft.Fields(); login.method="POST"; login.url=url("/login");
        login.headers.add(new RequestRow("X-CSRF-TOKEN","csrf-one"));
        var loginResult=sendWithCookies(login,settings,options,store,"csrf-test");
        assertEquals(200,loginResult.status()); assertTrue(received.get().contains("SESSION=session-one"));
        assertTrue(loginResult.curl().contains("Cookie: ••••"));
        assertFalse(loginResult.curl().contains("session-one")); assertFalse(loginResult.curl().contains("csrf-one"));
        assertFalse(loginResult.details().contains("session-one"));
        login.headers.getFirst().value="wrong-token";
        assertEquals("Wrong tokens must still be rejected",403,sendWithCookies(login,settings,options,store,"csrf-test").status());
        login.headers.getFirst().value="csrf-one";
        assertEquals("Other environments must not inherit this session",403,sendWithCookies(login,settings,options,store,"other-profile").status());
        login.headers.add(new RequestRow("Cookie","SESSION=manual"));
        assertEquals(403,sendWithCookies(login,settings,options,store,"csrf-test").status());
        assertEquals("SESSION=manual",received.get()); login.headers.removeLast();
        store.clear("csrf-test");
        assertEquals("Clearing cookies must reset the session",403,sendWithCookies(login,settings,options,store,"csrf-test").status());
    }
    public void testRequestEditorsCarryCsrfSessionBetweenTabs() throws Exception {
        server.createContext("/ui-csrf",exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie","SESSION=ui-session; Path=/; HttpOnly");
            exchange.getResponseHeaders().add("Content-Type","application/json");
            byte[] body="{\"data\":{\"token\":\"ui-csrf\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/ui-login",exchange -> {
            boolean valid=Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),"").contains("SESSION=ui-session")
                    && "ui-csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
            exchange.sendResponseHeaders(valid ? 200 : 403,-1); exchange.close();
        });
        var csrf=new RequestDraft(); csrf.fields.url=url("/ui-csrf");
        var login=new RequestDraft(); login.fields.url=url("/ui-login"); login.fields.method="POST";
        var csrfEditor=new RequestEditor(getProject(),csrf,() -> {},command -> {});
        var loginEditor=new RequestEditor(getProject(),login,() -> {},command -> {});
        try {
            csrfEditor.send(null); waitFor(() -> csrf.response!=null); assertEquals(200,csrf.response.status());
            String token=com.google.gson.JsonParser.parseString(csrf.response.body()).getAsJsonObject().getAsJsonObject("data").get("token").getAsString();
            // Enter the fresh returned token through the real editor table.
            var headerField=RequestEditor.class.getDeclaredField("headers"); headerField.setAccessible(true);
            ((KeyValueEditor)headerField.get(loginEditor)).addRow(new RequestRow("X-CSRF-TOKEN",token));
            loginEditor.send(null); waitFor(() -> login.response!=null);
            assertEquals("A separate API tab must reuse its environment's cookie",200,login.response.status());
        } finally { com.intellij.openapi.util.Disposer.dispose(csrfEditor); com.intellij.openapi.util.Disposer.dispose(loginEditor); }
    }
    public void testRedirectReevaluatesCookiePathInsteadOfForwardingCookieHeader() throws Exception {
        var store=getProject().getService(ApiCookies.class);
        var seen=new AtomicReference<String>();
        server.createContext("/private/start",exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("PRIVATE=hidden"));
            exchange.getResponseHeaders().add("Location","/public/end"); exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        server.createContext("/public/end",exchange -> {
            seen.set(Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),""));
            exchange.sendResponseHeaders(200,-1); exchange.close();
        });
        var fields=new RequestDraft.Fields(); fields.url=url("/private/start");
        store.jar("redirect-test",URI.create(fields.url)).put(URI.create(fields.url),java.util.Map.of("Set-Cookie",java.util.List.of("PRIVATE=hidden; Path=/private")));
        var result=sendWithCookies(fields,new ApiSettings(),new TransportOptions(2,5,true,5,65536),store,"redirect-test");
        assertEquals(200,result.status()); assertEquals("",seen.get());
    }
    private ExecutionResult sendWithCookies(RequestDraft.Fields fields,ApiSettings settings,TransportOptions options,ApiCookies store,String profile) throws Exception {
        var result=new AtomicReference<ExecutionResult>();
        var call=new RequestExecution(result::set,null); call.cookies(uri -> store.jar(profile,uri));
        call.start(() -> RequestComposer.prepare(fields,settings,options,true),options.totalSeconds()); waitFor(() -> result.get()!=null); return result.get();
    }
    public void testConnectionFailureExplainsActualHostPortAndRecovery() throws Exception {
        server.stop(0);
        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        run("/offline",new TransportOptions(2,5,false,5,65536),result);
        waitFor(() -> result.get()!=null);
        assertEquals("Connection error",result.get().outcome());
        assertTrue(result.get().body().contains("127.0.0.1:"+server.getAddress().getPort()));
        assertTrue(result.get().body().contains("application is running"));
        assertTrue(result.get().body().contains("Base URL in Global Environments"));
        assertTrue(RequestExecution.connectionMessage(new ConnectException("Connection refused"),"localhost:8080").startsWith("Connection refused by localhost:8080"));
        assertTrue(RequestExecution.connectionMessage(new ConnectException("Network unreachable"),"localhost:8080").startsWith("Could not reach localhost:8080"));
    }
    public void testCancellationAndDeadlineInterruptSlowBodies() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var stream = exchange.getResponseBody()) {
                stream.write('a'); stream.flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        try {
            AtomicReference<ExecutionResult> cancelled = new AtomicReference<>();
            var call = run("/slow", new TransportOptions(2, 5, false, 5, 65536), cancelled);
            call.stop("Cancelled");
            waitFor(() -> cancelled.get() != null);
            assertEquals("Cancelled", cancelled.get().outcome());
            AtomicReference<ExecutionResult> timeout = new AtomicReference<>();
            run("/slow", new TransportOptions(2, 1, false, 5, 65536), timeout);
            waitFor(() -> timeout.get() != null);
            assertEquals("Timeout", timeout.get().outcome());
        } finally { release.countDown(); }
    }
    public void testRedirectsAndPreviewLimit() throws Exception {
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/large");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[100_000]; java.util.Arrays.fill(bytes, (byte)'x');
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        run("/start", new TransportOptions(2, 5, true, 5, 65536), result);
        waitFor(() -> result.get() != null);
        assertEquals(200, result.get().status()); assertTrue(result.get().truncated());
        assertEquals(65536, result.get().body().length()); assertTrue(result.get().details().contains("Redirect 302"));
    }
}
