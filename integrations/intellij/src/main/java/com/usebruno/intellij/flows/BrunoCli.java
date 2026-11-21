package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.*;

final class BrunoCli {
    private BrunoCli() { }
    static List<String> command(Project project, FlowFile flow, String environment) {
        String base=project.getBasePath();
        return command(base==null || base.isBlank() ? null : Path.of(base),flow,environment,
                ApplicationManager.getApplication().getService(BrunoPreferences.class));
    }
    static List<String> command(Path projectRoot,FlowFile flow,String environment,BrunoPreferences preferences) {
        String relative=flow.collectionRoot().relativize(flow.path()).toString().replace('\\','/');
        String node=preferences.nodeExecutable.isBlank() ? "node" : preferences.nodeExecutable.strip();
        Path executable=null;
        if (!preferences.flowCliPath.isBlank()) {
            Path configured=Path.of(preferences.flowCliPath.strip());
            executable=Files.isDirectory(configured) ? scriptIn(configured) : configured;
            if (executable==null || !Files.isRegularFile(executable)) throw new IllegalArgumentException(
                    "Flow CLI path does not point to a CLI file or Bruno checkout. Configure it in Settings → Tools → Bruno.");
        } else {
            if (projectRoot!=null) executable=scriptIn(projectRoot);
            if (executable==null) executable=scriptIn(flow.collectionRoot());
            if (executable==null && !preferences.jsRuntimePath.isBlank()) {
                Path runtime=Path.of(preferences.jsRuntimePath.strip());
                for (Path candidate:List.of(runtime.resolveSibling("bruno-cli").resolve("bin/bru.js"),runtime.resolveSibling("cli").resolve("bin/bru.js"))) {
                    if (Files.isRegularFile(candidate)) { executable=candidate; break; }
                }
            }
        }
        List<String> result=new ArrayList<>();
        if (executable!=null) {
            executable=executable.toAbsolutePath().normalize();
            Path real=executable;
            try { real=executable.toRealPath(); } catch (java.io.IOException ignored) { }
            if (real.toString().endsWith(".js") || real.toString().endsWith(".cjs")) result.addAll(List.of(node,real.toString()));
            else if (isWindows() && (executable.toString().endsWith(".cmd") || executable.toString().endsWith(".bat"))) result.addAll(List.of("cmd","/c",executable.toString()));
            else result.add(executable.toString());
        } else if (isWindows()) result.addAll(List.of("cmd","/c","bru"));
        else result.add("bru");
        result.addAll(List.of("run","--flow",relative));
        if (environment!=null && !environment.isBlank()) result.addAll(List.of("--env",environment.strip()));
        return result;
    }
    private static Path scriptIn(Path root) {
        for (String relative:List.of("packages/bruno-cli/bin/bru.js","bin/bru.js","node_modules/@usebruno/cli/bin/bru.js",
                isWindows() ? "node_modules/.bin/bru.cmd" : "node_modules/.bin/bru")) {
            Path candidate=root.resolve(relative); if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
    private static boolean isWindows() { return com.intellij.openapi.util.SystemInfo.isWindows; }
}
