package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Uses Bruno's own QuickJS ScriptRuntime in a cancellable Node worker. */
final class ScriptHooks {
    private static final Gson JSON = new Gson();
    record Result(JsonObject request, JsonObject response, Map<String,String> variables, Set<String> deleted, String log) { }
    static Result run(Project project, String phase, String script, RequestDraft.Fields fields, ApiSettings settings,
                      JsonObject request, JsonObject response) throws Exception {
        var preferences = com.intellij.openapi.application.ApplicationManager.getApplication().getService(BrunoPreferences.class);
        Path module = locate(project, preferences.jsRuntimePath);
        Map<String,String> resolved = RequestComposer.variables(settings, fields, true);
        Set<String> secrets = secretNames(settings, fields); secrets.retainAll(resolved.keySet());
        JsonObject variables = new JsonObject();
        for (String key : resolved.keySet()) if (!secrets.contains(key)) variables.addProperty(key, resolved.get(key));
        JsonObject input = new JsonObject(); input.addProperty("modulePath", module.toString()); input.addProperty("phase", phase);
        input.addProperty("projectPath", project.getBasePath()); input.addProperty("script",script); input.add("request",request);
        input.add("response",response); input.add("variables",variables); var runtime = new JsonObject(); settings.session(fields.environment).values.forEach((key,value) -> { if (!secrets.contains(key)) runtime.addProperty(key,value); }); input.add("runtimeVariables",runtime); input.add("secretKeys",JSON.toJsonTree(secrets));
        Set<String> loadedSecrets = new HashSet<>();
        try (var worker = project.getService(ScriptWorker.class).acquire(preferences.nodeExecutable, module)) {
            worker.send(input);
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String line = worker.read();
                JsonObject message = JsonParser.parseString(line).getAsJsonObject(); String kind = message.get("kind").getAsString();
                if (kind.equals("secret")) {
                    String key = message.get("name").getAsString(); JsonObject reply = new JsonObject();
                    try { if (!secrets.contains(key)) throw new IllegalArgumentException(); reply.addProperty("value",resolved.get(key)); loadedSecrets.add(key); }
                    catch (Exception error) { reply.addProperty("error",true); }
                    worker.send(reply); continue;
                }
                if (kind.equals("error")) {
                    worker.complete();
                    String text = message.get("message").getAsString();
                    for (String key : loadedSecrets) { String value = resolved.get(key); if (value != null && !value.isEmpty()) text = text.replace(value,"••••"); }
                    throw new IOException("Bruno " + phase + " script failed: " + text);
                }
                if (!kind.equals("result")) continue;
                JsonObject result = message.getAsJsonObject("result");
                worker.complete();
                if (result.has("skipRequest") && result.get("skipRequest").getAsBoolean()) throw new IOException("Request skipped by pre-request script.");
                if (result.has("stopExecution") && result.get("stopExecution").getAsBoolean()) throw new IOException("Execution stopped by script.");
                if (result.has("scriptedRequestEntries") && !result.getAsJsonArray("scriptedRequestEntries").isEmpty()) throw new IOException("Collection request chaining is available in Bruno Flows, not standalone API hooks.");
                Map<String,String> updates = new LinkedHashMap<>();
                for (String scope : List.of("envVariables","runtimeVariables","globalEnvironmentVariables")) {
                    if (result.has(scope) && result.get(scope).isJsonObject()) result.getAsJsonObject(scope).entrySet().forEach(e -> updates.put(e.getKey(), value(e.getValue())));
                }
                Set<String> deleted = new HashSet<>(); message.getAsJsonArray("deleted").forEach(v -> deleted.add(v.getAsString()));
                String log = "Script tests: " + result.get("results") + "\n" + message.get("logs");
                for (String key : loadedSecrets) { String secret = resolved.get(key); if (secret != null && !secret.isEmpty()) log = log.replace(secret,"••••"); }
                for (var entry : updates.entrySet()) if ((secrets.contains(entry.getKey()) || sensitive(entry.getKey())) && !entry.getValue().isEmpty()) log = log.replace(entry.getValue(), "••••");
                return new Result(result.has("request") ? result.getAsJsonObject("request") : request,
                        result.has("response") ? result.getAsJsonObject("response") : response, updates, deleted, log);
            }
        }
    }
    static Path locate(Project project, String configured) throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (!configured.isBlank()) candidates.add(Path.of(configured));
        if (project.getBasePath() != null) {
            var root = Path.of(project.getBasePath()); candidates.add(root.resolve("node_modules/@usebruno/js")); candidates.add(root.resolve("packages/bruno-js"));
        }
        for (var path : candidates) if (Files.isRegularFile(path.resolve("package.json"))) return path.toAbsolutePath();
        throw new IOException("Bruno JS runtime not found. Set its package directory in Settings → Tools → Bruno (a local @usebruno/js installation or this fork's packages/bruno-js).");
    }
    static Set<String> secretNames(ApiSettings settings, RequestDraft.Fields fields) {
        Map<String,Boolean> flags = new HashMap<>();
        var rows = new ArrayList<RequestRow>(settings.defaults);
        settings.environments.stream().filter(e -> e.name.equals(fields.environment)).findFirst().ifPresent(e -> rows.addAll(e.variableRows)); rows.addAll(fields.variables);
        rows.stream().filter(r -> r.enabled).forEach(r -> flags.put(r.key,r.secret));
        settings.session(fields.environment).values.keySet().stream().filter(ScriptHooks::sensitive).forEach(key -> flags.put(key,true));
        Set<String> secrets = new HashSet<>(); flags.forEach((key,secret) -> { if (secret) secrets.add(key); }); return secrets;
    }
    private static final java.util.regex.Pattern CREDENTIAL=java.util.regex.Pattern.compile(".*(token|password|secret|authorization|cookie|api.?key).*",java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
    static boolean sensitive(String name) { return CREDENTIAL.matcher(name).matches(); }
    static void apply(Project project, ApiSettings snapshot, String environment, Result result) {
        var session = snapshot.session(environment); session.values.putAll(result.variables()); session.deleted.addAll(result.deleted());
        result.variables().keySet().forEach(session.deleted::remove); result.deleted().forEach(session.values::remove);
        String id = snapshot.environments.stream().filter(e -> e.name.equals(environment)).map(e -> e.id).findFirst().orElse(environment);
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            var global = ApiSettings.get(project); var live = global.sessions.computeIfAbsent(id, ignored -> new ApiSettings.Session());
            live.values.putAll(result.variables()); live.deleted.addAll(result.deleted()); result.variables().keySet().forEach(live.deleted::remove); result.deleted().forEach(live.values::remove); global.sessionChanged();
        });
    }
    static String value(JsonElement value) { return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString(); }
}
