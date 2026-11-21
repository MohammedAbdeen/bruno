package com.usebruno.intellij.flows;

import com.google.gson.Gson;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.*;

/** IDE-wide profiles; project services are retained only to migrate old data. */
@State(name = "BrunoGlobalEnvironments", storages = @Storage(value = "bruno-environments.xml", roamingType = RoamingType.DISABLED))
public final class GlobalApiSettings implements PersistentStateComponent<GlobalApiSettings> {
    private static final Gson JSON = new Gson();
    public ApiSettings settings = new ApiSettings();
    public List<String> migratedProjects = new ArrayList<>();

    synchronized ApiSettings forProject(Project project) {
        String key = project.getLocationHash();
        if (!migratedProjects.contains(key)) {
            merge(project.getService(ApiSettings.class), project.getName());
            migratedProjects.add(key);
        }
        return settings;
    }
    void merge(ApiSettings legacy, String projectName) {
        boolean pristine = settings.environments.size() == 1 && pristine(settings.environments.getFirst()) && settings.defaults.isEmpty();
        for (var old : legacy.environments) {
            if (pristine(old) && legacy.defaults.isEmpty()) continue;
            var imported = JSON.fromJson(JSON.toJson(old), ApiSettings.Environment.class);
            // Old project defaults apply only to that project's imported profiles.
            Set<String> explicit = new HashSet<>();
            imported.variableRows.stream().filter(r -> r.enabled).forEach(r -> explicit.add(r.key));
            if (!legacy.defaults.isEmpty()) {
                try { imported.migrate(); } catch (IllegalArgumentException ignored) { }
                imported.variableRows.stream().filter(r -> r.enabled).forEach(r -> explicit.add(r.key));
                for (var row : legacy.defaults) if (!explicit.contains(row.key)) imported.variableRows.add(row.copy());
            }
            imported.id = UUID.randomUUID().toString();
            String original = imported.name;
            if (pristine && original.equals("Local")) settings.environments.removeFirst();
            String name = original;
            int suffix = 1;
            while (contains(name)) name = original + " (" + projectName + (suffix++ == 1 ? "" : " " + (suffix - 1)) + ")";
            imported.name = name;
            settings.environments.add(imported);
            if (pristine && legacy.selected.equals(original)) settings.selected = name;
        }
        for (String ref : legacy.pendingSecretDeletions) if (!settings.pendingSecretDeletions.contains(ref)) settings.pendingSecretDeletions.add(ref);
        settings.changed();
    }
    private boolean contains(String name) { return settings.environments.stream().anyMatch(e -> e.name.equals(name)); }
    private static boolean pristine(ApiSettings.Environment env) {
        return env.name.equals("Local") && env.baseUrl.equals("http://localhost:8080") && env.variables.isEmpty()
                && env.variableRows.isEmpty() && (env.headers.isEmpty() || env.headers.equals("Accept=application/json"))
                && (env.headerRows.isEmpty() || env.headerRows.size() == 1 && env.headerRows.getFirst().key.equals("Accept")
                && env.headerRows.getFirst().value.equals("application/json") && env.headerRows.getFirst().enabled);
    }
    @Override public GlobalApiSettings getState() { return this; }
    @Override public void loadState(GlobalApiSettings state) { XmlSerializerUtil.copyBean(state, this); settings.changed(); }
}
