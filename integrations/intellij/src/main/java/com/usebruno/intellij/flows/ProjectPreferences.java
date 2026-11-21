package com.usebruno.intellij.flows;

import com.intellij.openapi.components.*;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

@State(name = "BrunoProjectPreferences", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ProjectPreferences implements PersistentStateComponent<ProjectPreferences> {
    public Integer connectSeconds;
    public Integer totalSeconds;
    public Boolean redirects;
    public Integer redirectLimit;
    public Integer previewKiB;
    public Boolean restoreTabs;
    @Override public ProjectPreferences getState() { return this; }
    @Override public void loadState(@NotNull ProjectPreferences state) { XmlSerializerUtil.copyBean(state, this); }
}
