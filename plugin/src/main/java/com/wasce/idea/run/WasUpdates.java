package com.wasce.idea.run;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.task.ProjectTaskManager;
import com.wasce.core.deploy.UpdatePlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新入口：先让 IDE 增量编译，再把更新放入会话队列。只更新资源或重新部署时不需要 IDE 编译。
 */
public final class WasUpdates {
    private WasUpdates() {
    }

    public static List<WasSessionProcessHandler> running(Project project) {
        List<WasSessionProcessHandler> result = new ArrayList<>();
        for (ProcessHandler h : ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (h instanceof WasSessionProcessHandler w && !w.isProcessTerminated()) {
                result.add(w);
            }
        }
        return result;
    }

    public static void update(Project project, WasSessionProcessHandler handler, UpdatePlanner.Mode mode) {
        if (mode != UpdatePlanner.Mode.CLASSES_AND_RESOURCES) {
            handler.session().update(mode);
            return;
        }
        ProjectTaskManager.getInstance(project).buildAllModules().onSuccess(result -> {
            if (result.isAborted() || result.hasErrors()) {
                notify(project, "编译未通过，已取消更新", NotificationType.WARNING);
            } else {
                handler.session().update(mode);
            }
        });
    }

    static void notify(Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("WebSphere CE")
                .createNotification(message, type).notify(project);
    }
}
