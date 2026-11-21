package com.usebruno.intellij.flows;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.psi.util.PsiModificationTracker;

/** Accessed only under a read action; cached values contain no PSI objects. */
@Service(Service.Level.PROJECT)
public final class EndpointDiscovery {
    private final Project project;
    private long revision = -1;
    private SpringEndpointScanner.Result cached;
    public EndpointDiscovery(Project project) { this.project = project; }
    synchronized SpringEndpointScanner.Result scan() {
        long current = PsiModificationTracker.getInstance(project).getModificationCount();
        if (cached == null || revision != current) {
            var result = SpringEndpointScanner.scan(project);
            cached = result; revision = current;
        }
        return cached;
    }
}
