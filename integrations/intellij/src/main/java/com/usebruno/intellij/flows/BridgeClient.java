package com.usebruno.intellij.flows;

import com.google.gson.*;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;

final class BridgeClient {
    private static final Gson JSON = new Gson();
    static URI base(String value) throws Exception {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getHost() == null || !List.of("", "/").contains(Objects.toString(uri.getPath(),""))) throw new IllegalArgumentException("Use a loopback HTTP bridge URL, e.g. http://127.0.0.1:19090.");
        for (var address : InetAddress.getAllByName(uri.getHost())) if (!address.isLoopbackAddress()) throw new IllegalArgumentException("The development bridge must run on loopback.");
        return uri;
    }
    static JsonElement call(HttpClient client, String url, String token, String path, JsonObject payload) throws Exception {
        URI uri = base(url).resolve(path);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).header("Authorization","Bearer " + token);
        if (payload != null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        try {
            var response = client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(24 * 1024 * 1024 + 1); }
            if (bytes.length > 24 * 1024 * 1024) throw new IllegalArgumentException("Bridge response exceeds the 24 MiB transport limit.");
            if (response.statusCode() == 401) throw new IllegalArgumentException("Bridge authentication failed. Check the development bridge token.");
            var json = JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalArgumentException("Development bridge error: " + json);
            return json;
        } catch (ConnectException error) { throw new IllegalArgumentException("Could not connect to the development bridge. Start the application with bruno.bridge.enabled=true and check its loopback port."); }
    }
    static HttpResponse<InputStream> invoke(HttpClient client, HttpRequest original, String url, String tokenRef, String principal, String expectedController) throws Exception {
        if (tokenRef.isBlank()) throw new IllegalArgumentException("Configure the development bridge and token before invoking without filters.");
        String token;
        try { token = SecretVault.read(tokenRef); } catch (Exception error) { throw new IllegalArgumentException("Saved bridge token is unavailable. Reopen Development Bridge settings and enter it again."); }
        return invokeWithToken(client,original,url,token,principal,expectedController);
    }
    static HttpResponse<InputStream> invokeWithToken(HttpClient client, HttpRequest original, String url, String token, String principal) throws Exception {
        return invokeWithToken(client,original,url,token,principal,"");
    }
    static HttpResponse<InputStream> invokeWithToken(HttpClient client, HttpRequest original, String url, String token, String principal, String expectedController) throws Exception {
        var payload = new JsonObject(); payload.addProperty("url",original.uri().toString()); payload.addProperty("method",original.method());
        payload.add("headers",JSON.toJsonTree(original.headers().map())); payload.addProperty("principal",principal); payload.addProperty("controller",expectedController);
        payload.addProperty("body",Base64.getEncoder().encodeToString(body(original)));
        return decodeResponse(original,call(client,url,token,"/invoke",payload));
    }
    static HttpResponse<InputStream> decodeResponse(HttpRequest original, JsonElement value) {
        try {
        var response = value.getAsJsonObject();
        byte[] body = Base64.getDecoder().decode(response.get("body").getAsString());
        Map<String,List<String>> headers = new LinkedHashMap<>(); response.getAsJsonObject("headers").entrySet().forEach(e -> {
            var values = new ArrayList<String>(); e.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString())); headers.put(e.getKey(),values);
        });
        int status = response.get("status").getAsInt();
        if (status < 100 || status > 599) throw new IllegalArgumentException();
        var parsed = HttpHeaders.of(headers,(key,headerValue) -> true);
        return new HttpResponse<>() {
            public int statusCode() { return status; } public HttpRequest request() { return original; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return parsed; } public InputStream body() { return new ByteArrayInputStream(body); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); } public URI uri() { return original.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid development bridge response. Expected status, headers and a base64 body; check the companion version."); }
    }
    static byte[] body(HttpRequest request) throws Exception {
        if (request.bodyPublisher().isEmpty()) return new byte[0];
        var future = new CompletableFuture<byte[]>(); var subscription = new AtomicReference<Flow.Subscription>();
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<>() {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
            public void onNext(ByteBuffer buffer) {
                if (buffer.remaining() > 8 * 1024 * 1024 - output.size()) { subscription.get().cancel(); future.completeExceptionally(new IOException("Bridge request bodies are limited to 8 MiB.")); return; }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes); subscription.get().request(1);
            }
            public void onError(Throwable error) { future.completeExceptionally(error); }
            public void onComplete() { future.complete(output.toByteArray()); }
        });
        try { return future.get(30,TimeUnit.SECONDS); } catch (ExecutionException error) { throw new IllegalArgumentException(error.getCause().getMessage()); } finally { var active = subscription.get(); if (active != null) active.cancel(); }
    }
}
