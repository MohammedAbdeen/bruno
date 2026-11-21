package com.usebruno.intellij.flows;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class BrunoCloseAction extends DumbAwareAction {
    static final DataKey<Runnable> CLOSE = DataKey.create("Bruno.closeRequest");
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    private static boolean canClose(AnActionEvent event) {
        var context = event.getData(PlatformDataKeys.CONTEXT_COMPONENT);
        // Scope the shortcut to Bruno, including its embedded text editors.
        return event.getData(CLOSE) != null && context != null
                && (context instanceof ApiExplorerPanel
                    || javax.swing.SwingUtilities.getAncestorOfClass(ApiExplorerPanel.class, context) != null);
    }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(canClose(event)); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var close = event.getData(CLOSE); if (canClose(event) && close != null) close.run();
    }
}
