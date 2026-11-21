package com.usebruno.intellij.flows;

import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.SwingUtilities;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class RequestExecution {
    private static final java.util.regex.Pattern CHARSET = java.util.regex.Pattern.compile("charset\\s*=\\s*\"?([^;\"\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    @FunctionalInterface interface ResponseProcessor { ExecutionResult process(ExecutionResult response) throws Exception; }
    @FunctionalInterface interface RequestSender { HttpResponse<InputStream> send(HttpClient client, HttpRequest request) throws Exception; }
    private final ResponseProcessor processor;
    private final RequestSender sender;
    private String transport = "HTTP";
    private java.util.function.Function<URI,CookieManager> cookies;
    void cookies(java.util.function.Function<URI,CookieManager> provider) { cookies=provider; }
    void transport(String description) { transport = description; }
    private final long start = System.nanoTime();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Consumer<ExecutionResult> callback;
    private volatile Future<?> work;
    private volatile ScheduledFuture<?> timeout;
    private volatile InputStream stream;
    private volatile HttpClient client;
    private volatile String capturedCurl = "";
    private volatile String target = "the configured host and port";
    private volatile String details = "";
    private volatile long bytesRead;
    private volatile int lastStatus;
    private final Path download;

    RequestExecution(Consumer<ExecutionResult> callback, Path download) { this(callback, download, value -> value); }
    RequestExecution(Consumer<ExecutionResult> callback, Path download, ResponseProcessor processor) {
        this(callback,download,processor,(client,request) -> client.send(request,HttpResponse.BodyHandlers.ofInputStream()));
    }
    RequestExecution(Consumer<ExecutionResult> callback, Path download, ResponseProcessor processor, RequestSender sender) {
        this.callback = callback; this.download = download; this.processor = processor; this.sender = sender;
    }

    void start(Callable<RequestComposer.Prepared> prepare, int deadlineSeconds) {
        work = AppExecutorUtil.getAppExecutorService().submit(() -> {
            try { execute(prepare.call()); }
            catch (Exception error) {
                String category = error instanceof java.net.http.HttpTimeoutException ? "Timeout"
                        : error instanceof javax.net.ssl.SSLException ? "TLS error" : error instanceof ConnectException ? "Connection error"
                        : error instanceof InterruptedException ? "Cancelled" : "Request failed";
                String message;
                if (error instanceof IllegalArgumentException) {
                    message = error.getMessage();
                } else if (error instanceof ConnectException) {
                    message = connectionMessage(error, target);
                } else if (error instanceof java.net.http.HttpTimeoutException) {
                    message = "Request timed out waiting for server response.";
                } else if (error instanceof java.net.UnknownHostException) {
                    message = "Unknown host. Check host name or DNS configuration.";
                } else if (error instanceof javax.net.ssl.SSLException) {
                    message = "TLS/SSL handshake failed. Check certificates and protocol configuration.";
                } else if (error instanceof InterruptedException) {
                    message = "Request was cancelled.";
                } else {
                    message = error.getClass().getSimpleName();
                }
                complete(new ExecutionResult(0, category, elapsed(), 0, message, "", details, false, false, ""));
            } finally { closeResources(); }
        });
        timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> stop("Timeout"), deadlineSeconds, TimeUnit.SECONDS);
        if (finished.get()) timeout.cancel(false);
    }

    static String connectionMessage(Throwable error, String target) {
        String reason = "Could not connect to ";
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            String text = Objects.toString(error.getMessage(), "").toLowerCase(Locale.ROOT);
            if (text.contains("refused")) { reason = "Connection refused by "; break; }
            if (text.contains("unreachable") || text.contains("no route")) { reason = "Could not reach "; break; }
        }
        return reason + target + ". Check that your application is running and its port matches Base URL in Global Environments.";
    }

    private void execute(RequestComposer.Prepared prepared) throws Exception {
        if (finished.get()) return;
        capturedCurl = prepared.curl();
        var uri = prepared.request().uri();
        target = prepared.redact(uri.getHost() + ":" + (uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort()));
        details = "Started: " + Instant.now() + "\nTransport: " + transport + "\n" + prepared.details();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(prepared.options().connectSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = prepared.request();
        HttpResponse<InputStream> response;
        int redirects = 0;
        while (true) {
            if (finished.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            CookieManager jar = cookies == null ? null : cookies.apply(request.uri());
            HttpRequest sent = request;
            if (jar != null && request.headers().firstValue("Cookie").isEmpty()) {
                var cookieHeaders = jar.get(request.uri(),request.headers().map());
                if (!cookieHeaders.isEmpty() && cookieHeaders.values().stream().anyMatch(v -> !v.isEmpty())) {
                    var withCookies=HttpRequest.newBuilder(request,(key,value) -> true);
                    cookieHeaders.forEach((key,values) -> values.forEach(value -> withCookies.header(key,value)));
                    sent=withCookies.build();
                    if (redirects == 0) {
                        capturedCurl += " --header 'Cookie: ••••'";
                        details += "\nSession cookie header: •••• (project/environment/origin cookie jar)\n";
                    }
                }
            }
            response = sender.send(client,sent);
            stream = response.body();
            if (jar != null) jar.put(request.uri(),response.headers().map());
            if (finished.get()) { stream.close(); return; }
            int status = response.statusCode();
            lastStatus = status;
            Optional<String> location = response.headers().firstValue("location");
            if (!prepared.options().redirects() || !List.of(301, 302, 303, 307, 308).contains(status) || location.isEmpty()) break;
            if (++redirects > prepared.options().redirectLimit()) throw new IllegalArgumentException("Redirect limit exceeded.");
            URI next = request.uri().resolve(location.get());
            if (!List.of("http", "https").contains(next.getScheme()) || next.getUserInfo() != null)
                throw new IllegalArgumentException("Unsupported redirect target.");
            if (request.uri().getScheme().equals("https") && next.getScheme().equals("http")) {
                details += "\nHTTPS-to-HTTP redirect not followed.\n"; break;
            }
            boolean sameOrigin = origin(request.uri()).equals(origin(next));
            // Cross-origin redirects require review. This also prevents custom credential headers/body from leaking.
            if (!sameOrigin) { details += "\nCross-origin redirect not followed; review Location before sending.\n"; break; }
            stream.close();
            boolean toGet = status == 303 && !request.method().equals("HEAD")
                    || (status == 301 || status == 302) && request.method().equals("POST");
            var builder = HttpRequest.newBuilder(next).timeout(request.timeout().orElse(Duration.ofSeconds(30)))
                    .method(toGet ? "GET" : request.method(), toGet ? HttpRequest.BodyPublishers.noBody()
                            : request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.headers().map().forEach((key, values) -> {
                if (!(toGet && key.equalsIgnoreCase("Content-Type"))) values.forEach(value -> builder.header(key, value));
            });
            details += "\nRedirect " + status + " → " + prepared.redact(next.toString()) + "\n";
            request = builder.build();
        }
        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        long total = 0;
        boolean truncated = false;
        int limit = prepared.options().previewBytes();
        try (InputStream input = stream;
             OutputStream output = download == null ? OutputStream.nullOutputStream() : Files.newOutputStream(download, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (finished.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                total += count;
                bytesRead = total;
                output.write(buffer, 0, count);
                preview.write(buffer, 0, Math.min(count, Math.max(0, limit - preview.size())));
                if (total > limit) {
                    truncated = true;
                    if (download == null) break;
                }
            }
        }
        String contentType = response.headers().firstValue("content-type").orElse("text/plain");
        Charset charset = StandardCharsets.UTF_8;
        var match = CHARSET.matcher(contentType);
        if (match.find()) try { charset = Charset.forName(match.group(1)); } catch (Exception ignored) { }
        String mediaType = contentType.toLowerCase(Locale.ROOT);
        boolean binary = !(mediaType.startsWith("text/") || mediaType.contains("json") || mediaType.contains("xml")
                || mediaType.contains("javascript") || mediaType.contains("x-www-form-urlencoded"));
        String body = binary ? "Binary response (" + contentType + "). Use Send & Download to save the response."
                : new String(preview.toByteArray(), charset);
        StringBuilder headers = new StringBuilder();
        response.headers().map().forEach((key, values) -> values.forEach(v -> headers.append(key).append(": ").append(v).append("\n")));
        details += "\nHTTP version: " + response.version() + "\nResponse content type: " + contentType
                + "\nCharset: " + charset.name() + "\nBytes read: " + total + "\nPreview truncated: " + truncated
                + "\nSaved response: " + (download == null ? "No" : download);
        complete(processor.process(new ExecutionResult(response.statusCode(), "HTTP " + response.statusCode(), elapsed(), total, body,
                headers.toString(), details, truncated, binary, download == null ? "" : download.toString())
                .withPayload(contentType, binary && !truncated ? preview.toByteArray() : new byte[0])));
    }

    static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":"
                + (uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort());
    }
    private long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
    private void complete(ExecutionResult result) {
        if (!finished.compareAndSet(false, true)) return;
        if (timeout != null) timeout.cancel(false);
        var captured = result.withCurl(capturedCurl);
        SwingUtilities.invokeLater(() -> callback.accept(captured));
    }
    void stop(String reason) {
        complete(new ExecutionResult(lastStatus, reason, elapsed(), bytesRead,
                reason + "\nNo complete response was captured." + (download == null ? "" : "\nThe download may be partial."),
                "", details + "\nExecution ended early; response may be partial.", false, false, download == null ? "" : download.toString()));
        if (work != null) work.cancel(true);
        AppExecutorUtil.getAppExecutorService().execute(this::closeResources);
    }
    private void closeResources() {
        InputStream current = stream;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        if (client != null) client.shutdownNow();
    }
}
