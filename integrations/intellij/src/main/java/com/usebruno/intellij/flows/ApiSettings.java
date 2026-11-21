package com.usebruno.intellij.flows;

import com.intellij.openapi.components.*;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@State(name = "BrunoApiEnvironments", storages = {@Storage(value = "bruno-environments.xml", roamingType = RoamingType.DISABLED), @Storage(value = StoragePathMacros.WORKSPACE_FILE, deprecated = true)})
public final class ApiSettings implements PersistentStateComponent<ApiSettings> {
    static final com.intellij.util.messages.Topic<Runnable> CHANGED = com.intellij.util.messages.Topic.create("Bruno global environments", Runnable.class);
    private static final java.util.concurrent.atomic.AtomicLong REVISIONS = new java.util.concurrent.atomic.AtomicLong();
    public static ApiSettings get(com.intellij.openapi.project.Project project) {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(GlobalApiSettings.class).forProject(project);
    }
    private transient volatile long modification;
    long revision() { return modification; }
    private transient volatile long profileModification;
    long profilesRevision() { return profileModification; }
    void changed() { profileModification = REVISIONS.incrementAndGet(); signal(); }
    void sessionChanged() { signal(); }
    private void signal() {
        modification = REVISIONS.incrementAndGet();
        var app = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (app != null && !app.isDisposed()) javax.swing.SwingUtilities.invokeLater(() -> {
            if (!app.isDisposed()) app.getMessageBus().syncPublisher(CHANGED).run();
        });
    }
    static final class Session {
        final Map<String,String> values = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<String> deleted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }
    @com.intellij.util.xmlb.annotations.Transient
    public transient Map<String,Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    Session session(String environment) {
        String id = environments.stream().filter(e -> e.name.equals(environment)).map(e -> e.id).findFirst().orElse(environment);
        return sessions.computeIfAbsent(id, ignored -> new Session());
    }
    void copySessions(ApiSettings source) {
        source.sessions.forEach((id, old) -> { var value = new Session(); value.values.putAll(old.values); value.deleted.addAll(old.deleted); sessions.put(id,value); });
    }
    public int version = 2;
    public String projectId = UUID.randomUUID().toString();
    public List<String> pendingSecretDeletions = new ArrayList<>();
    public List<RequestRow> defaults = new ArrayList<>();
    public String selected = "Local";
    public List<Environment> environments = new ArrayList<>(List.of(new Environment()));

    public static class Environment {
        public String id = UUID.randomUUID().toString();
        public String name = "Local";
        public String baseUrl = "http://localhost:8080";
        public String variables = "";
        public String headers = "Accept=application/json";
        public boolean structured;
        public List<RequestRow> variableRows = new ArrayList<>();
        public List<RequestRow> headerRows = new ArrayList<>();

        public void migrate() {
            if (structured) return;
            // Validate both before replacing anything so malformed legacy text is retained.
            var vars = BulkRows.parse(variables, List.of(), true);
            var hdrs = BulkRows.parse(headers, List.of(), false);
            variableRows = new ArrayList<>(vars); headerRows = new ArrayList<>(hdrs);
            structured = true;
        }
    }

    @Override public ApiSettings getState() { return this; }
    @Override public void loadState(@NotNull ApiSettings state) { XmlSerializerUtil.copyBean(state, this); changed(); }
}
