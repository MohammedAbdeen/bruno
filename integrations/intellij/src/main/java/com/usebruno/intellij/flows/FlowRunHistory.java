package com.usebruno.intellij.flows;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Project-session history; raw CLI reports are never retained on disk. */
@Service(Service.Level.PROJECT)
public final class FlowRunHistory implements Disposable {
    static final int MAX_REPORT=2*1024*1024, MAX_BYTES=5*1024*1024, MAX_RUNS=10;
    private static final java.util.regex.Pattern CREDENTIAL=java.util.regex.Pattern.compile(".*(token|password|secret|authorization|cookie|api.?key).*",java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
    private boolean disposed;
    private long retainedBytes;
    private final List<Run> runs=new ArrayList<>();
    record Run(String id,Instant started,String flow,String environment,String status,long duration,String report,String console,long retainedBytes) {
        Run(String id,Instant started,String flow,String environment,String status,long duration,String report,String console) {
            this(id,started,flow,environment,status,duration,report,console,(long)report.getBytes(StandardCharsets.UTF_8).length+console.getBytes(StandardCharsets.UTF_8).length);
        }
        long bytes() { return retainedBytes; }
        @Override public String toString() { return started.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)+" · "+Path.of(flow).getFileName()+" · "+status+" · "+duration+" ms"; }
    }
    static String readReport(Path file) throws Exception {
        if (file==null || !Files.isRegularFile(file)) return "{}";
        byte[] bytes;
        try (var input=Files.newInputStream(file)) { bytes=input.readNBytes(MAX_REPORT+1); }
        if (bytes.length==0) return "{}";
        if (bytes.length>MAX_REPORT) return "{\"error\":\"Input/output report exceeded the 2 MiB history limit; see Console.\"}";
        return normalize(new String(bytes,StandardCharsets.UTF_8)).toString();
    }
    static JsonObject summary(String text) throws java.io.IOException {
        JsonElement parsed=FlowJsonImport.parseJson(text);
        if (!parsed.isJsonObject()) throw new java.io.IOException("Flow report must be an object.");
        var root=parsed.getAsJsonObject();
        if (root.has("summary")) {
            if (!root.get("summary").isJsonObject()) throw new java.io.IOException("Flow report summary must be an object.");
            root=root.getAsJsonObject("summary");
        }
        validateSteps(root); return root;
    }
    private static void validateSteps(JsonObject report) throws java.io.IOException {
        if (!report.has("steps")) return;
        if (!report.get("steps").isJsonArray()) throw new java.io.IOException("Flow report steps must be an array.");
        for (var entry:report.getAsJsonArray("steps")) {
            if (!entry.isJsonObject()) throw new java.io.IOException("Flow report step must be an object.");
            var step=entry.getAsJsonObject();
            if (step.has("subFlow") && !step.get("subFlow").isJsonNull()) {
                if (!step.get("subFlow").isJsonObject()) throw new java.io.IOException("Subflow report must be an object.");
                validateSteps(step.getAsJsonObject("subFlow"));
            }
        }
    }
    private static JsonObject normalize(String text) throws java.io.IOException {
        var root=summary(text); removeDuplicates(root); redact(root,0); return root;
    }
    private static void removeDuplicates(JsonObject summary) {
        summary.remove("nodeResults");
        if (summary.has("steps") && summary.get("steps").isJsonArray()) for (var step:summary.getAsJsonArray("steps"))
            if (step.isJsonObject() && step.getAsJsonObject().has("subFlow") && step.getAsJsonObject().get("subFlow").isJsonObject()) removeDuplicates(step.getAsJsonObject().getAsJsonObject("subFlow"));
    }
    private static boolean sensitive(String key) { return CREDENTIAL.matcher(key).matches(); }
    private static void redact(JsonElement value,int depth) {
        if (value==null || value.isJsonNull()) return;
        if (depth>=48) return;
        if (value.isJsonObject()) {
            var object=value.getAsJsonObject();
            String header=object.has("name") && object.get("name").isJsonPrimitive() ? object.get("name").getAsString()
                    : object.has("key") && object.get("key").isJsonPrimitive() ? object.get("key").getAsString() : "";
            for (String key:new ArrayList<>(object.keySet())) {
                var child=object.get(key);
                if (sensitive(key) || (key.equals("value") && sensitive(header))) object.addProperty(key,"••••");
                else if (depth==47 && (child.isJsonObject() || child.isJsonArray())) object.addProperty(key,"[Nested data omitted]");
                else if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString() && Set.of("body","data").contains(key)) {
                    String text=child.getAsString().stripLeading();
                    if (text.startsWith("{") || text.startsWith("[")) try {
                        if (depth>=47) { object.addProperty(key,"[Nested data omitted]"); continue; }
                        var nested=FlowJsonImport.parseJson(text); redact(nested,depth+1);
                        object.addProperty(key,nested.toString());
                    } catch (java.io.IOException ignored) { }
                } else redact(child,depth+1);
            }
        } else if (value.isJsonArray()) {
            var array=value.getAsJsonArray();
            for (int i=0;i<array.size();i++) {
                var child=array.get(i);
                if (depth==47 && (child.isJsonObject() || child.isJsonArray())) array.set(i,new JsonPrimitive("[Nested data omitted]"));
                else redact(child,depth+1);
            }
        }
    }
    Run add(Instant started,String flow,String environment,int exitCode,long duration,String report,String console) {
        JsonObject summary;
        try { summary=normalize(report); }
        catch (Exception error) { summary=new JsonObject(); summary.addProperty("error","Execution details unavailable: "+error.getMessage()); }
        String status=exitCode==0 ? "success" : "failed";
        var value=summary.get("status");
        if (value!=null && value.isJsonPrimitive() && Set.of("success","failed","cancelled").contains(value.getAsString())) status=value.getAsString();
        Run run=new Run(UUID.randomUUID().toString(),started,flow,environment,status,duration,summary.toString(),console);
        synchronized (this) {
            if (disposed) return run;
            runs.add(0,run); retainedBytes+=run.bytes();
            while (runs.size()>MAX_RUNS || (runs.size()>1 && retainedBytes>MAX_BYTES)) retainedBytes-=runs.removeLast().bytes();
        }
        return run;
    }
    synchronized List<Run> runs() { return List.copyOf(runs); }
    synchronized void clear() { runs.clear(); retainedBytes=0; }
    @Override public synchronized void dispose() { disposed=true; clear(); }
}
