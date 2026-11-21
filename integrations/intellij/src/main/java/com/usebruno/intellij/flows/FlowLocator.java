package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.*;
import java.nio.file.Path;
import java.util.*;

final class FlowLocator {
    private static final Set<String> IGNORED = Set.of(".git", ".idea", "node_modules", "build", "dist");
    private static final List<String> MARKERS = List.of("bruno.json", "opencollection.yml", "opencollection.yaml");
    private record Entry(VirtualFile file, VirtualFile collection) { }
    private FlowLocator() { }

    private static boolean isFlow(String name) {
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".flow.bru") || name.endsWith(".flow.yml") || name.endsWith(".flow.yaml");
    }
    static List<FlowFile> findFlows(Path projectRoot) throws java.io.IOException {
        var fs = LocalFileSystem.getInstance();
        var root = fs.findFileByNioFile(projectRoot.toAbsolutePath().normalize());
        if (root == null) {
            root = ApplicationManager.getApplication().isDispatchThread()
                    ? WriteAction.compute(() -> fs.refreshAndFindFileByNioFile(projectRoot))
                    : fs.refreshAndFindFileByNioFile(projectRoot);
        }
        return root == null ? List.of() : findFlows(root);
    }
    static List<FlowFile> findFlows(VirtualFile root) {
        return ReadAction.compute(() -> {
            List<FlowFile> flows = new ArrayList<>();
            if (!root.isValid() || !root.isDirectory()) return List.of();
            Deque<Entry> pending = new ArrayDeque<>(); pending.push(new Entry(root, null));
            while (!pending.isEmpty()) {
                ProgressManager.checkCanceled();
                var entry = pending.pop(); var dir = entry.file(); var collection = entry.collection();
                for (String marker : MARKERS) {
                    var file = dir.findChild(marker);
                    if (file != null && !file.isDirectory()) { collection = dir; break; }
                }
                for (var file : dir.getChildren()) {
                    if (file.isDirectory()) {
                        if (!IGNORED.contains(file.getName()) && !file.is(VFileProperty.SYMLINK)) pending.push(new Entry(file, collection));
                    } else if (collection != null && isFlow(file.getName())) {
                        flows.add(new FlowFile(Path.of(file.getPath()), Path.of(collection.getPath())));
                    }
                }
            }
            flows.sort(Comparator.comparing(f -> f.path().toString().toLowerCase(Locale.ROOT)));
            return List.copyOf(flows);
        });
    }
}
