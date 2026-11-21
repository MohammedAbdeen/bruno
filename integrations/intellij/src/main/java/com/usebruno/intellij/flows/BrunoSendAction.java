package com.usebruno.intellij.flows;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class BrunoSendAction extends DumbAwareAction {
    static final DataKey<RequestEditor> EDITOR = DataKey.create("Bruno.requestEditor");
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(@NotNull AnActionEvent event) {
        var editor = event.getData(EDITOR); event.getPresentation().setEnabled(editor != null && !editor.running());
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var editor = event.getData(EDITOR); if (editor != null && !editor.running()) editor.send(null);
    }
}
