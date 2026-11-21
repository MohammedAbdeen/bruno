package com.usebruno.intellij.flows;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

record TransportOptions(int connectSeconds, int totalSeconds, boolean redirects, int redirectLimit, int previewBytes) {
    static TransportOptions effective(Project project, RequestDraft.Fields request) {
        var defaults = ApplicationManager.getApplication().getService(BrunoPreferences.class);
        var local = project.getService(ProjectPreferences.class);
        int connect = request.overrideTransport ? request.connectSeconds
                : (local.connectSeconds != null ? local.connectSeconds : defaults.connectSeconds);
        int total = request.overrideTransport ? request.totalSeconds
                : (local.totalSeconds != null ? local.totalSeconds : defaults.totalSeconds);
        boolean redirects = request.overrideTransport ? request.redirects
                : (local.redirects != null ? local.redirects : defaults.redirects);
        int redirectLimit = local.redirectLimit != null ? local.redirectLimit : defaults.redirectLimit;
        int previewBytes = (local.previewKiB != null ? local.previewKiB : defaults.previewKiB) * 1024;
        var options = new TransportOptions(connect, total, redirects, redirectLimit, previewBytes);
        options.validate();
        return options;
    }
    void validate() {
        if (connectSeconds < 1 || connectSeconds > 120 || totalSeconds < 1 || totalSeconds > 600
                || redirectLimit < 1 || redirectLimit > 10 || previewBytes < 65536 || previewBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid timeout, redirect limit, or response limit.");
        }
    }
}
