package com.wasce.idea.run;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.wasce.core.deploy.UpdatePlanner;

/**
 * 窗口失去焦点时按运行配置触发更新。短时间内的重复触发由会话队列合并。
 */
public final class WasFrameListener implements ApplicationActivationListener {
    @Override
    public void applicationDeactivated(IdeFrame frame) {
        Project project = frame.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        for (WasSessionProcessHandler h : WasUpdates.running(project)) {
            if ("RESOURCES".equals(h.frameUpdate())) {
                WasUpdates.update(project, h, UpdatePlanner.Mode.RESOURCES);
            } else if ("CLASSES".equals(h.frameUpdate())) {
                WasUpdates.update(project, h, UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
            }
        }
    }
}
