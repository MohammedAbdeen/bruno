package com.usebruno.intellij.flows;

import com.intellij.notification.*;
import com.intellij.openapi.project.Project;

final class BrunoNotifications {
    static void error(Project project, String message) { show(project, message, NotificationType.ERROR); }
    static void info(Project project, String message) { show(project, message, NotificationType.INFORMATION); }
    private static void show(Project project, String message, NotificationType type) {
        if (project != null && project.isDisposed()) return;
        NotificationGroupManager.getInstance().getNotificationGroup("Bruno")
                .createNotification("Bruno", message == null ? "Operation failed. See the request details." : message, type).notify(project);
    }
}
