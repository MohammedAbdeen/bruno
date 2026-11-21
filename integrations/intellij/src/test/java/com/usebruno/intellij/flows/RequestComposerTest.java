package com.usebruno.intellij.flows;

import org.junit.Test;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class RequestComposerTest {
    private final TransportOptions options = new TransportOptions(15, 30, false, 5, 1024 * 1024);
    @Test public void layeredVariablesAndPathQueryValuesResolveOnce() throws Exception {
        ApiSettings settings = new ApiSettings();
        settings.defaults.add(new RequestRow("id", "default"));
        var env = settings.environments.getFirst(); env.variables = "id=env\nquery=a/b";
        RequestDraft.Fields fields = new RequestDraft.Fields();
        fields.url = "{{baseUrl}}/users/{{id}}";
        fields.path.add(new RequestRow("id", ""));
        fields.variables.add(new RequestRow("id", "a/b"));
        fields.query.add(new RequestRow("tag", "{{query}}")); fields.query.add(new RequestRow("tag", "✓"));
        assertEquals("http://localhost:8080/users/a%2Fb?tag=a%2Fb&tag=%E2%9C%93",
                RequestComposer.prepare(fields, settings, options, true).request().uri().toString());
    }
    @Test public void headerOverridesPreserveRepeatsAndAuthWins() throws Exception {
        ApiSettings settings = new ApiSettings();
        settings.environments.getFirst().headers = "X-Mode=default\nAuthorization=old";
        RequestDraft.Fields fields = new RequestDraft.Fields();
        fields.headers.add(new RequestRow("x-mode", "one")); fields.headers.add(new RequestRow("X-Mode", "two"));
        fields.headers.add(new RequestRow("Authorization", "request"));
        fields.auth = "Bearer"; fields.authValue = "secret";
        var prepared = RequestComposer.prepare(fields, settings, options, true);
        assertEquals(List.of("one", "two"), prepared.request().headers().allValues("X-Mode"));
        assertEquals("Bearer secret", prepared.request().headers().firstValue("Authorization").orElseThrow());
        assertFalse(prepared.details().contains("Bearer secret"));
    }
    @Test public void disabledOverrideSuppressesInheritedHeaderAndSingletonsAreRejected() throws Exception {
        ApiSettings settings = new ApiSettings(); settings.environments.getFirst().headers = "X-Test=default";
        var fields = new RequestDraft.Fields(); var disabled = new RequestRow("x-test", "ignored"); disabled.enabled = false;
        fields.headers.add(disabled);
        assertTrue(RequestComposer.prepare(fields, settings, options, true).request().headers().allValues("x-test").isEmpty());
        fields.headers.add(new RequestRow("Authorization", "a")); fields.headers.add(new RequestRow("authorization", "b"));
        assertThrows(IllegalArgumentException.class, () -> RequestComposer.prepare(fields, settings, options, true));
    }
    @Test public void invalidJsonAndMissingVariablesFailBeforeSend() {
        var fields = new RequestDraft.Fields(); fields.bodyMode = "JSON"; fields.body = "{broken}";
        assertThrows(IllegalArgumentException.class, () -> RequestComposer.prepare(fields, new ApiSettings(), options, true));
        fields.body = "{\"value\":\"{{missing}}\"}";
        assertThrows(IllegalArgumentException.class, () -> RequestComposer.prepare(fields, new ApiSettings(), options, true));
        assertThrows(IllegalArgumentException.class, () -> JsonSupport.validate("{'not':'json'}"));
    }
    @Test public void unusedSectionsAndOverriddenDefaultsDoNotBlockRequest() throws Exception {
        var settings = new ApiSettings();
        settings.environments.getFirst().headers = "Authorization=Bearer {{unused}}";
        var fields = new RequestDraft.Fields();
        fields.bodyMode = "Form"; fields.body = "{{oldBodyVariable}}";
        fields.auth = "Bearer"; fields.authValue = "valid";
        fields.path.add(new RequestRow("oldId", "{{unusedPath}}"));
        assertEquals("http://localhost:8080/", RequestComposer.preview(fields, settings));
        assertEquals("Bearer valid", RequestComposer.prepare(fields, settings, options, true).request().headers().firstValue("Authorization").orElseThrow());
        fields.auth = "None"; fields.headers.add(new RequestRow("Authorization", "direct"));
        fields.authValue = "{{unusedAuth}}";
        assertEquals("direct", RequestComposer.prepare(fields, settings, options, true).request().headers().firstValue("Authorization").orElseThrow());
    }
    @Test public void unusedOrOverriddenSecretsDoNotAccessCredentialStore() throws Exception {
        var settings = new ApiSettings();
        var secret = new RequestRow("unused", ""); secret.secret = true; secret.secretRef = "missing-reference";
        settings.defaults.add(secret);
        settings.environments.getFirst().variables = "unused=replacement";
        var fields = new RequestDraft.Fields();
        assertEquals("http://localhost:8080/", RequestComposer.prepare(fields, settings, options, true).request().uri().toString());
    }
    @Test public void formAndMultipartRequestsReachServerWithTheirPayloads() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, request.length);
            try (var output = exchange.getResponseBody()) { output.write(request); }
        });
        server.start();
        var file = Files.createTempFile("bruno-upload", ".txt"); Files.writeString(file, "file-content");
        try (var client = HttpClient.newHttpClient()) {
            var fields = new RequestDraft.Fields(); fields.method = "POST";
            fields.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            fields.bodyMode = "Form"; fields.form.add(new RequestRow("tag", "a/b")); fields.form.add(new RequestRow("tag", "two"));
            var form = RequestComposer.prepare(fields, new ApiSettings(), options, true);
            assertEquals("tag=a%2Fb&tag=two", client.send(form.request(), HttpResponse.BodyHandlers.ofString()).body());
            fields.bodyMode = "Multipart";
            var upload = new RequestRow("attachment", file.toString()); upload.description = "file"; fields.form.add(upload);
            var multipart = RequestComposer.prepare(fields, new ApiSettings(), options, true);
            String body = client.send(multipart.request(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("file-content")); assertTrue(body.contains("name=\"attachment\""));
            assertTrue(multipart.request().headers().firstValue("content-type").orElseThrow().contains("boundary="));
        } finally { server.stop(0); Files.delete(file); }
    }
}
