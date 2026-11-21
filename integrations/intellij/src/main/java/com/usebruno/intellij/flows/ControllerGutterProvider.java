package com.usebruno.intellij.flows;

import com.intellij.codeInsight.daemon.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public final class ControllerGutterProvider extends RelatedItemLineMarkerProvider {
    private static final com.intellij.openapi.util.Key<com.intellij.psi.util.CachedValue<List<ApiEndpoint>>> CACHE = com.intellij.openapi.util.Key.create("Bruno.controller.endpoints");
    @Override public String getName() { return "Bruno controller requests"; }
    @Override protected void collectNavigationMarkers(PsiElement element, Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        if (!(element instanceof PsiIdentifier) || DumbService.isDumb(element.getProject())) return;
        var parent = element.getParent();
        PsiClass controller;
        PsiMethod method = null;
        if (parent instanceof PsiMethod m && m.getNameIdentifier() == element) { method = m; controller = m.getContainingClass(); }
        else if (parent instanceof PsiClass c && c.getNameIdentifier() == element) controller = c;
        else return;
        if (controller == null) return;
        List<ApiEndpoint> endpoints = endpoints(controller, method);
        if (endpoints.isEmpty()) return;
        String label = endpoints.size() == 1 ? endpoints.getFirst().method() + " " + endpoints.getFirst().path() : endpoints.size() + " controller endpoints";
        result.add(new RelatedItemLineMarkerInfo<>(element, element.getTextRange(), AllIcons.Actions.Execute,
                ignored -> "Open in Bruno: " + label,
                (event, ignored) -> {
                    if (endpoints.size() == 1) open(element.getProject(), endpoints.getFirst());
                    else JBPopupFactory.getInstance().createPopupChooserBuilder(endpoints)
                            .setTitle("Open API in Bruno")
                            .setRenderer(new DefaultListCellRenderer() {
                                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                                    super.getListCellRendererComponent(list, value, index, selected, focus);
                                    if (value instanceof ApiEndpoint endpoint) setText(endpoint.method() + " " + endpoint.path());
                                    return this;
                                }
                            }).setItemChosenCallback(endpoint -> open(element.getProject(), endpoint)).createPopup().show(new com.intellij.ui.awt.RelativePoint(event));
                }, GutterIconRenderer.Alignment.LEFT, List::of));
    }
    static List<ApiEndpoint> endpoints(PsiClass controller, PsiMethod method) {
        var file = controller.getContainingFile().getVirtualFile();
        if (file == null || com.intellij.openapi.roots.ProjectFileIndex.getInstance(controller.getProject()).isInTestSourceContent(file)) return List.of();
        List<ApiEndpoint> found = com.intellij.psi.util.CachedValuesManager.getManager(controller.getProject()).getCachedValue(controller, CACHE, () -> {
            var endpoints = new ArrayList<ApiEndpoint>();
            SpringEndpointScanner.inspectClass(controller, endpoints, new ArrayList<>());
            String name = Objects.toString(controller.getQualifiedName(), controller.getName());
            return com.intellij.psi.util.CachedValueProvider.Result.create(endpoints.stream().filter(e -> e.controller().equals(name)).toList(),
                    com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT);
        }, false);
        return found.stream().filter(e -> method == null || e.offset() == method.getTextOffset()
                && e.fileUrl().equals(method.getContainingFile().getVirtualFile().getUrl())).toList();
    }
    static void open(Project project, ApiEndpoint endpoint) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            var window = ToolWindowManager.getInstance(project).getToolWindow("Bruno");
            if (window == null) { BrunoNotifications.error(project, "The Bruno tool window is unavailable."); return; }
            window.activate(() -> {
                for (var content : window.getContentManager().getContents()) if (content.getComponent() instanceof ApiExplorerPanel explorer) {
                    window.getContentManager().setSelectedContent(content); explorer.openEndpoint(endpoint); return;
                }
            });
        });
    }
}
