package com.usebruno.intellij.flows;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.google.gson.JsonParser;

final class RequestComposer {
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
    private static final class Variables extends LinkedHashMap<String, String> {
        final Map<String, String> references = new HashMap<>();
        final Map<String, String> loaded = new HashMap<>();
        final boolean reveal;
        Variables(boolean reveal) { this.reveal = reveal; }
        @Override public String get(Object key) {
            String reference = references.get(key);
            if (reference == null) return super.get(key);
            if (!reveal) return "••••";
            return loaded.computeIfAbsent(reference, SecretVault::read);
        }
    }
    record Prepared(HttpRequest request, TransportOptions options, String details, List<String> secrets, String curl) {
        String redact(String value) { return redact(value, secrets); }
        static String redact(String value, List<String> secretList) {
            String result = value;
            for (String secret : secretList) if (!secret.isEmpty()) {
                result = result.replace(secret, "••••").replace(QueryCodec.encode(secret), "••••");
            }
            return result;
        }
    }

    static Map<String, String> variables(ApiSettings settings, RequestDraft.Fields fields, boolean reveal) {
        ApiSettings.Environment environment = settings.environments.stream().filter(e -> e.name.equals(fields.environment))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Environment not found: " + fields.environment));
        environment.migrate();
        Variables variables = new Variables(reveal);
        addVariables(variables, settings.defaults, reveal);
        addVariables(variables, environment.variableRows, reveal);
        addVariables(variables, fields.variables, reveal);
        var session = settings.session(fields.environment);
        session.values.forEach((key,value) -> {
            boolean secret = variables.references.containsKey(key) || ScriptHooks.sensitive(key);
            variables.put(key, !reveal && secret ? "••••" : value); variables.references.remove(key);
            if (reveal && secret) variables.loaded.put("session:" + key, value);
        });
        session.deleted.forEach(key -> { variables.remove(key); variables.references.remove(key); });
        variables.put("baseUrl", environment.baseUrl.replaceAll("/+$", ""));
        return variables;
    }

    private static void addVariables(Variables vars, List<RequestRow> rows, boolean reveal) {
        Set<String> names = new HashSet<>();
        for (RequestRow row : rows) if (row.enabled && !row.key.isBlank()) {
            if (!names.add(row.key)) throw new IllegalArgumentException("Duplicate variable: " + row.key);
            if (row.key.equals("baseUrl")) throw new IllegalArgumentException("baseUrl is reserved. Use the Base URL field.");
            vars.put(row.key, row.secret ? "••••" : row.value);
            vars.references.remove(row.key);
            if (row.secret) vars.references.put(row.key, row.secretRef);
        }
    }

    static String resolvedUrl(RequestDraft.Fields fields, Map<String, String> variables) {
        Map<String, String> pathVars = new HashMap<>();
        var split = QueryCodec.split(fields.url);
        Set<String> names = new HashSet<>(), used = new HashSet<>();
        var placeholders = PLACEHOLDER.matcher(split.base());
        while (placeholders.find()) used.add(placeholders.group(1));
        for (RequestRow row : fields.path) if (!row.key.isBlank()
                && used.contains(row.key)) {
            if (!names.add(row.key)) throw new IllegalArgumentException("Duplicate path variable: " + row.key);
            String value = !row.enabled || row.value.isEmpty() ? variables.get(row.key) : RequestTemplates.expand(row.value, variables);
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing path variable: " + row.key);
            pathVars.put(row.key, QueryCodec.encode(RequestTemplates.expand(value, variables)));
        }
        Map<String, String> pathScope = new AbstractMap<>() {
            @Override public String get(Object key) { return pathVars.containsKey(key) ? pathVars.get(key) : variables.get(key); }
            @Override public boolean containsKey(Object key) { return pathVars.containsKey(key) || variables.containsKey(key); }
            @Override public Set<Entry<String, String>> entrySet() { return variables.entrySet(); }
        };
        String url = RequestTemplates.expand(split.base(), pathScope);
        List<RequestRow> rows = new ArrayList<>();
        List<RequestRow> query = fields.query.isEmpty() ? split.rows() : fields.query;
        for (RequestRow row : query) if (row.enabled && !row.key.isBlank()) {
            rows.add(new RequestRow(RequestTemplates.expand(row.key, variables), RequestTemplates.expand(row.value, variables)));
        }
        if (fields.auth.equals("API key") && fields.authLocation.equals("Query"))
            rows.add(new RequestRow(fields.authKey, RequestTemplates.expand(fields.authValue, variables)));
        StringJoiner encoded = new StringJoiner("&");
        for (RequestRow row : rows) encoded.add(QueryCodec.encode(row.key) + "=" + QueryCodec.encode(row.value));
        return url + (encoded.length() == 0 ? "" : "?" + encoded);
    }

    static Prepared prepare(RequestDraft.Fields fields, ApiSettings settings, TransportOptions options, boolean reveal) throws Exception {
        options.validate();
        Map<String, String> vars = variables(settings, fields, reveal);
        String resolved = resolvedUrl(fields, vars);
        // Preview masks are valid text but not URI-safe; actual sending always resolves real secrets.
        URI uri;
        try { uri = URI.create(reveal ? resolved : resolved.replace("••••", "MASKED")); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("The resolved URL is invalid. Check the host and encoded parameter values."); }
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter an HTTP(S) URL without credentials in its authority.");
        var environment = settings.environments.stream().filter(e -> e.name.equals(fields.environment)).findFirst().orElseThrow();
        List<RequestRow> headers = effectiveHeaders(environment.headerRows, fields.headers, vars, authHeaderNames(fields));
        if (fields.auth.equals("Bearer")) replace(headers, "Authorization", "Bearer " + RequestTemplates.expand(fields.authValue, vars));
        if (fields.auth.equals("Basic")) replace(headers, "Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (RequestTemplates.expand(fields.authUser, vars) + ":" + RequestTemplates.expand(fields.authValue, vars)).getBytes(StandardCharsets.UTF_8)));
        if (fields.auth.equals("API key") && fields.authLocation.equals("Header"))
            replace(headers, fields.authKey, RequestTemplates.expand(fields.authValue, vars));

        List<RequestRow> curlForm = new ArrayList<>();
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        String content = List.of("JSON", "Text").contains(fields.bodyMode) ? RequestTemplates.expand(fields.body, vars) : "";
        if (fields.bodyMode.equals("JSON")) {
            JsonSupport.validate(content);
            publisher = HttpRequest.BodyPublishers.ofString(content);
            defaultHeader(headers, "Content-Type", "application/json");
        } else if (fields.bodyMode.equals("Text")) {
            publisher = HttpRequest.BodyPublishers.ofString(content);
            defaultHeader(headers, "Content-Type", "text/plain; charset=utf-8");
        } else if (fields.bodyMode.equals("Form")) {
            StringJoiner form = new StringJoiner("&");
            for (RequestRow row : fields.form) if (row.enabled && !row.key.isBlank()) {
                form.add(QueryCodec.encode(RequestTemplates.expand(row.key, vars)) + "=" + QueryCodec.encode(RequestTemplates.expand(row.value, vars)));
            }
            content = form.toString();
            publisher = HttpRequest.BodyPublishers.ofString(content);
            defaultHeader(headers, "Content-Type", "application/x-www-form-urlencoded");
        } else if (fields.bodyMode.equals("Multipart")) {
            String boundary = "Bruno" + UUID.randomUUID().toString().replace("-", "");
            List<HttpRequest.BodyPublisher> pieces = new ArrayList<>();
            for (RequestRow row : fields.form) if (row.enabled && !row.key.isBlank()) {
                String key = RequestTemplates.expand(row.key, vars);
                var curlRow = new RequestRow(key, RequestTemplates.expand(row.value, vars)); curlRow.description = row.description; curlForm.add(curlRow);
                if (key.matches(".*[\"\\r\\n].*")) throw new IllegalArgumentException("Invalid multipart field name.");
                String start = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + key + "\"";
                if (row.description.equals("file")) {
                    Path file = Path.of(RequestTemplates.expand(row.value, vars));
                    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Multipart file is unavailable: " + row.key);
                    String name = file.getFileName().toString().replaceAll("[\"\\r\\n]", "_");
                    pieces.add(HttpRequest.BodyPublishers.ofString(start + "; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n"));
                    pieces.add(HttpRequest.BodyPublishers.ofFile(file));
                } else {
                    pieces.add(HttpRequest.BodyPublishers.ofString(start + "\r\n\r\n" + RequestTemplates.expand(row.value, vars)));
                }
                pieces.add(HttpRequest.BodyPublishers.ofString("\r\n"));
            }
            pieces.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
            publisher = HttpRequest.BodyPublishers.concat(pieces.toArray(HttpRequest.BodyPublisher[]::new));
            replace(headers, "Content-Type", "multipart/form-data; boundary=" + boundary);
        }
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(options.totalSeconds())).method(fields.method, publisher);
        for (RequestRow header : headers) {
            try { builder.header(header.key, header.value); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid or restricted HTTP header: " + header.key); }
        }
        var request = builder.build();
        List<String> secrets = new ArrayList<>();
        if (vars instanceof Variables resolvedVariables) secrets.addAll(resolvedVariables.loaded.values());
        if (!fields.auth.equals("None") && !fields.authValue.isBlank()) secrets.add(RequestTemplates.expand(fields.authValue, vars));
        StringBuilder detail = new StringBuilder(fields.method + " " + resolved + "\nEnvironment: " + fields.environment
                + "\nConnect timeout: " + options.connectSeconds() + "s\nTotal deadline: " + options.totalSeconds()
                + "s\nRedirects: " + (options.redirects() ? options.redirectLimit() + " maximum" : "Off")
                + "\nBody: " + fields.bodyMode + " (" + publisher.contentLength() + " bytes; -1 means streamed)"
                + "\n\nEffective request headers:\n");
        for (var header : headers) detail.append(header.key).append(": ").append(sensitive(header.key) ? "••••" : header.value)
                .append(" [").append(header.description).append("]\n");
        detail.append("\nConnection/TLS timing breakdown: Not available\n");
        var secretsList = List.copyOf(secrets);
        return new Prepared(request, options, Prepared.redact(detail.toString(), secretsList), secretsList, Prepared.redact(CurlCommands.create(request, content, curlForm, secretsList), secretsList));
    }

    static boolean sensitive(String key) {
        return List.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-csrf-token", "x-xsrf-token").contains(key.toLowerCase(Locale.ROOT));
    }
    // Expand active fields even when only the URL is displayed: missing variables
    // in headers, auth or the body must disable Send before execution.
    static String preview(RequestDraft.Fields fields, ApiSettings settings) {
        var vars = variables(settings, fields, false);
        var env = settings.environments.stream().filter(e -> e.name.equals(fields.environment)).findFirst().orElseThrow();
        effectiveHeaders(env.headerRows, fields.headers, vars, authHeaderNames(fields));
        if (List.of("JSON", "Text").contains(fields.bodyMode)) RequestTemplates.expand(fields.body, vars);
        if (!fields.auth.equals("None")) {
            RequestTemplates.expand(fields.authValue, vars);
            if (fields.auth.equals("Basic")) RequestTemplates.expand(fields.authUser, vars);
        }
        if (List.of("Form", "Multipart").contains(fields.bodyMode))
            for (var row : fields.form) if (row.enabled && !row.key.isBlank()) {
                RequestTemplates.expand(row.key, vars); RequestTemplates.expand(row.value, vars);
            }
        return resolvedUrl(fields, vars);
    }
    private static Set<String> authHeaderNames(RequestDraft.Fields fields) {
        if (List.of("Bearer", "Basic").contains(fields.auth)) return Set.of("authorization");
        if (fields.auth.equals("API key") && fields.authLocation.equals("Header")) return Set.of(fields.authKey.toLowerCase(Locale.ROOT));
        return Set.of();
    }
    private static List<RequestRow> effectiveHeaders(List<RequestRow> defaults, List<RequestRow> overrides, Map<String, String> vars, Set<String> authOverrides) {
        Set<String> replaced = new HashSet<>();
        for (var row : overrides) if (!row.key.isBlank()) replaced.add(row.key.toLowerCase(Locale.ROOT));
        List<RequestRow> result = new ArrayList<>();
        for (var row : defaults) if (!replaced.contains(row.key.toLowerCase(Locale.ROOT)) && !authOverrides.contains(row.key.toLowerCase(Locale.ROOT)))
            addHeader(result, row, vars, "environment");
        for (var row : overrides) if (!authOverrides.contains(row.key.toLowerCase(Locale.ROOT))) addHeader(result, row, vars, "request");
        for (String singleton : List.of("authorization", "host", "content-length")) {
            if (result.stream().filter(r -> r.key.equalsIgnoreCase(singleton)).count() > 1)
                throw new IllegalArgumentException("Duplicate singleton header: " + singleton);
        }
        return result;
    }
    private static void addHeader(List<RequestRow> target, RequestRow row, Map<String, String> vars, String source) {
        if (!row.enabled || row.key.isBlank()) return;
        var copy = new RequestRow(row.key, RequestTemplates.expand(row.value, vars));
        copy.description = source; target.add(copy);
    }
    private static void replace(List<RequestRow> rows, String key, String value) {
        rows.removeIf(r -> r.key.equalsIgnoreCase(key));
        var row = new RequestRow(key, value); row.description = "authentication / body"; rows.add(row);
    }
    private static void defaultHeader(List<RequestRow> rows, String key, String value) {
        if (rows.stream().noneMatch(r -> r.key.equalsIgnoreCase(key))) replace(rows, key, value);
    }
}
