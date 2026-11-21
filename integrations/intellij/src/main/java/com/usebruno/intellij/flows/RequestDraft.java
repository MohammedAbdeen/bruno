package com.usebruno.intellij.flows;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RequestDraft {
    public String id = UUID.randomUUID().toString();
    public String endpointId = "";
    public String controller = "";
    public String sourceUrl = "";
    public int sourceOffset;
    public String sourcePath = "";
    public String sourceMethod = "";
    public String name = "New request";
    public boolean open = true;
    public boolean pinned;
    public boolean detached;
    public boolean copied;
    public String selectedSection = "Params";
    public long lastUsed;
    public String baseline = "";
    public Fields fields = new Fields();
    // Responses are deliberately session-local; request drafts and explicit saves persist.
    @com.intellij.util.xmlb.annotations.Transient
    public transient ExecutionResult response;
    @com.intellij.util.xmlb.annotations.Transient
    public transient Fields lastExecution;
    @com.intellij.util.xmlb.annotations.Transient
    public transient String lastController = "";
    @com.intellij.util.xmlb.annotations.Transient
    public transient List<ExecutionResult> history = new ArrayList<>();
    void recordResponse(ExecutionResult value) {
        response = value; history.add(0, value);
        long bytes = 0;
        for (int i = 0; i < history.size(); i++) {
            var entry = history.get(i);
            bytes += entry.payloadSize() + 2L * (entry.body().length() + entry.headers().length() + entry.details().length() + entry.curl().length());
            if (i >= 10 || i > 0 && bytes > 5L * 1024 * 1024) { history.subList(i, history.size()).clear(); break; }
        }
    }

    public static final class Fields {
        public String method = "GET";
        public String url = "{{baseUrl}}/";
        public String environment = "Local";
        public List<RequestRow> headers = new ArrayList<>();
        public List<RequestRow> query = new ArrayList<>();
        public List<RequestRow> path = new ArrayList<>();
        public List<RequestRow> variables = new ArrayList<>();
        public boolean bypassFilters;
        public String bridgePrincipal = "";
        public String preScript = "";
        public String postScript = "";
        public String bodyMode = "None";
        public String body = "";
        public List<RequestRow> form = new ArrayList<>();
        public String auth = "None";
        public String authValue = "";
        public String authUser = "";
        public String authKey = "X-API-Key";
        public String authLocation = "Header";
        public boolean overrideTransport;
        public int connectSeconds = 15;
        public int totalSeconds = 30;
        public boolean redirects;
    }

    private static final Gson JSON = new com.google.gson.GsonBuilder().addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
        public boolean shouldSkipField(com.google.gson.FieldAttributes field) {
            return field.getDeclaringClass() == RequestRow.class && field.getName().equals("id");
        }
        public boolean shouldSkipClass(Class<?> type) { return false; }
    }).create();
    public String snapshot() { return JSON.toJson(fields); }
    private transient String comparedBaseline;
    private transient Fields savedFields;
    private static final java.lang.reflect.Field[] FIELD_MEMBERS = Fields.class.getFields();
    private static final java.lang.reflect.Field[] ROW_MEMBERS = java.util.Arrays.stream(RequestRow.class.getFields())
            .filter(f -> !f.getName().equals("id") && !java.lang.reflect.Modifier.isTransient(f.getModifiers())).toArray(java.lang.reflect.Field[]::new);
    public boolean dirty() {
        if (baseline.isEmpty()) return true;
        if (!baseline.equals(comparedBaseline)) {
            savedFields = JSON.fromJson(baseline, Fields.class); comparedBaseline = baseline;
        }
        try {
            for (var field : FIELD_MEMBERS) {
                Object current = field.get(fields), saved = field.get(savedFields);
                if (current instanceof List<?> rows && saved instanceof List<?> old) {
                    if (rows.size() != old.size()) return true;
                    for (int i = 0; i < rows.size(); i++) for (var member : ROW_MEMBERS)
                        if (!java.util.Objects.equals(member.get(rows.get(i)), member.get(old.get(i)))) return true;
                } else if (!java.util.Objects.equals(current, saved)) return true;
            }
            return false;
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
    public void save() { baseline = snapshot(); }
    public void revert() { if (!baseline.isEmpty()) fields = JSON.fromJson(baseline, Fields.class); }
    public RequestDraft duplicateLastExecution() {
        var draft = duplicate();
        if (lastExecution != null) { draft.fields = JSON.fromJson(JSON.toJson(lastExecution), Fields.class); draft.controller = lastController; }
        draft.save(); return draft;
    }
    public RequestDraft duplicate() {
        RequestDraft draft = JSON.fromJson(JSON.toJson(this), RequestDraft.class);
        draft.id = UUID.randomUUID().toString();
        draft.name = name + " (copy)";
        draft.open = true; draft.pinned = false; draft.copied = true;
        return draft;
    }
}
