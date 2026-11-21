package com.usebruno.intellij.flows;

import com.intellij.openapi.components.*;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@State(name = "BrunoDefaults", storages = @Storage("bruno.xml"))
public final class BrunoPreferences implements PersistentStateComponent<BrunoPreferences> {
    public static final class BridgeConnection {
        public String bridgeUrl = "http://127.0.0.1:19090";
        public String bridgeTokenRef = "";
    }
    public java.util.Map<String,BridgeConnection> bridges = new java.util.LinkedHashMap<>();
    static BridgeConnection bridge(com.intellij.openapi.project.Project project) {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(BrunoPreferences.class).bridges
                .computeIfAbsent(project.getLocationHash(), ignored -> new BridgeConnection());
    }
    public String nodeExecutable = "node";
    public String jsRuntimePath = "";
    public String flowCliPath = "";
    public int connectSeconds = 15;
    public int totalSeconds = 30;
    public boolean redirects;
    public int redirectLimit = 5;
    public int previewKiB = 1024;
    public boolean restoreTabs = true;
    public boolean responseSoftWrap = true;
    @Override public BrunoPreferences getState() { return this; }
    @Override public void loadState(@NotNull BrunoPreferences state) { XmlSerializerUtil.copyBean(state, this); }
}
