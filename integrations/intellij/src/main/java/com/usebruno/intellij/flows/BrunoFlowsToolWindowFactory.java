package com.usebruno.intellij.flows;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class BrunoFlowsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ApiExplorerPanel explorer = new ApiExplorerPanel(project);
        Content apiContent = ContentFactory.getInstance().createContent(explorer, "APIs", false);
        apiContent.setCloseable(false);
        apiContent.setDisposer(explorer);
        toolWindow.getContentManager().addContent(apiContent);
        BrunoFlowsPanel panel = new BrunoFlowsPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Flows", false);
        content.setCloseable(false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
