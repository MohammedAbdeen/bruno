package com.usebruno.intellij.flows;

import com.intellij.openapi.components.*;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import java.util.*;

@State(name = "BrunoRequestWorkspace", storages = {@Storage(value = "bruno-requests.xml", roamingType = RoamingType.DISABLED), @Storage(value = StoragePathMacros.WORKSPACE_FILE, deprecated = true)})
public final class WorkspaceState implements PersistentStateComponent<WorkspaceState> {
    public int version = 2;
    static final int RETAINED_DRAFTS = 100;
    private static final java.util.regex.Pattern PATH_VARIABLE = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}");
    public List<RequestDraft> drafts = new ArrayList<>();
    public List<String> flowCollections = new ArrayList<>();
    public String activeId = "";
    public String search = "";
    public String module = "All modules";
    public boolean welcomeDismissed;
    public boolean endpointOpened;
    public boolean treeInitialized;
    public double explorerProportion = 0.28;
    public boolean sideBySide;
    public double editorProportion = 0.55;
    public List<String> expanded = new ArrayList<>();

    public RequestDraft openEndpoint(ApiEndpoint endpoint, String environment) {
        for (RequestDraft draft : drafts) {
            if (draft.endpointId.equals(endpoint.identity()) && !draft.copied) {
                draft.controller = endpoint.controller();
                draft.open = true; draft.lastUsed = System.currentTimeMillis(); activeId = draft.id;
                return draft;
            }
        }
        RequestDraft draft = new RequestDraft();
        draft.endpointId = endpoint.identity(); draft.controller = endpoint.controller();
        draft.sourceUrl = endpoint.fileUrl(); draft.sourceOffset = endpoint.offset();
        draft.sourcePath = endpoint.path(); draft.sourceMethod = endpoint.method();
        draft.name = endpoint.path();
        draft.fields.method = endpoint.method().equals("ANY") ? "GET" : endpoint.method();
        draft.fields.url = "{{baseUrl}}" + endpoint.path()
                .replaceAll("(?<![{$])\\{([\\w]+)(?::[^{}]*)?}(?!})", "{{$1}}");
        draft.fields.environment = environment;
        var matcher = PATH_VARIABLE.matcher(draft.fields.url);
        while (matcher.find()) if (!matcher.group(1).equals("baseUrl")) draft.fields.path.add(new RequestRow(matcher.group(1), ""));
        draft.save(); drafts.add(draft); draft.lastUsed = System.currentTimeMillis(); activeId = draft.id; prune();
        return draft;
    }

    void close(RequestDraft draft) {
        draft.open = false; draft.response = null; draft.lastExecution = null; draft.history.clear(); draft.lastUsed = System.currentTimeMillis(); prune();
    }
    void prune() {
        int excess = drafts.size() - RETAINED_DRAFTS;
        if (excess <= 0) return;
        var candidates = drafts.stream().filter(d -> !d.open && !d.pinned && !d.dirty())
                .sorted(Comparator.comparingLong(d -> d.lastUsed)).limit(excess).toList();
        drafts.removeAll(candidates);
    }
    @Override public WorkspaceState getState() { return this; }
    @Override public void loadState(@NotNull WorkspaceState state) {
        XmlSerializerUtil.copyBean(state, this);
        drafts.stream().filter(d -> !d.open).forEach(d -> d.response = null);
        prune();
        if (explorerProportion < 0.15 || explorerProportion > 0.85) {
            explorerProportion = 0.28;
        }
        if (editorProportion < 0.2 || editorProportion > 0.8) {
            editorProportion = 0.55;
        }
    }
}
