package com.wasce.idea.run;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.wasce.core.deploy.UpdatePlanner;

import java.util.List;
import java.util.Map;

/**
 * Update 动作：菜单入口作用于所有运行中的会话；Console 工具栏入口绑定到具体会话。
 */
public final class WasUpdateAction extends AnAction {
    private static final Map<String, UpdatePlanner.Mode> MODES = new java.util.LinkedHashMap<>();

    static {
        MODES.put("更新类和资源", UpdatePlanner.Mode.CLASSES_AND_RESOURCES);
        MODES.put("只更新资源", UpdatePlanner.Mode.RESOURCES);
        MODES.put("重新部署", UpdatePlanner.Mode.REDEPLOY);
    }

    private final WasSessionProcessHandler handler;

    public WasUpdateAction() {
        this(null);
    }

    WasUpdateAction(WasSessionProcessHandler handler) {
        super("Update WebSphere Application", "同步改动到服务器", AllIcons.Actions.Refresh);
        this.handler = handler;
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(handler != null ? !handler.isProcessTerminated()
                : project != null && !WasUpdates.running(project).isEmpty());
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        List<WasSessionProcessHandler> targets = handler != null ? List.of(handler) : WasUpdates.running(project);
        JBPopupFactory.getInstance().createPopupChooserBuilder(List.copyOf(MODES.keySet()))
                .setTitle("更新 WebSphere 应用")
                .setItemChosenCallback(choice -> targets.forEach(h -> WasUpdates.update(project, h, MODES.get(choice))))
                .createPopup()
                .showCenteredInCurrentWindow(project);
    }
}
