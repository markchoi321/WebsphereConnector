package com.wasce.idea.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.JBTable;
import com.wasce.core.deploy.AppState;
import com.wasce.idea.run.WasSessionProcessHandler;
import com.wasce.idea.run.WasUpdateAction;
import com.wasce.idea.run.WasUpdates;

import javax.swing.Icon;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 应用列表。状态来自会话的只读快照，每秒刷新；所有操作都只是放入会话队列，不阻塞 EDT。
 */
final class WasAppsPanel extends SimpleToolWindowPanel implements Disposable {
    private record Row(WasSessionProcessHandler handler, String app, AppState state) {
    }

    private final Project project;
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final Timer timer = new Timer(1000, e -> refresh());

    private final class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"会话", "服务器状态", "应用", "应用状态"};
        private List<Row> rows = List.of();

        void set(List<Row> newRows) {
            if (!newRows.equals(rows)) {
                int selected = table.getSelectedRow();
                rows = newRows;
                fireTableDataChanged();
                if (selected >= 0 && selected < rows.size()) {
                    table.getSelectionModel().setSelectionInterval(selected, selected);
                }
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row r = rows.get(row);
            return switch (column) {
                case 0 -> r.handler().displayName();
                case 1 -> r.handler().session().state().label();
                case 2 -> r.app();
                default -> r.state().label();
            };
        }
    }

    WasAppsPanel(Project project) {
        super(true, true);
        this.project = project;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getEmptyText().setText("没有运行中的 WebSphere 应用");
        setContent(ScrollPaneFactory.createScrollPane(table));

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new WasUpdateAction());
        group.addSeparator();
        group.add(appAction("启动应用", AllIcons.Actions.Execute, r -> r.handler().session().startApp(r.app())));
        group.add(appAction("停止应用", AllIcons.Actions.Suspend, r -> r.handler().session().stopApp(r.app())));
        group.add(appAction("重启应用", AllIcons.Actions.Restart, r -> r.handler().session().restartApp(r.app())));
        group.add(appAction("重新构建并部署", AllIcons.Actions.Compile, r -> r.handler().session().redeploy(r.app())));
        group.add(appAction("卸载应用", AllIcons.General.Remove, this::uninstall));
        group.addSeparator();
        group.add(appAction("在浏览器中打开", AllIcons.Actions.Preview, r -> {
            String url = r.handler().appUrl(r.app());
            if (url != null) {
                BrowserUtil.browse(url);
            }
        }));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("WasCeApps", group, true);
        toolbar.setTargetComponent(this);
        setToolbar(toolbar.getComponent());
        timer.start();
        refresh();
    }

    private void uninstall(Row r) {
        if (Messages.showYesNoDialog(project, "确定从服务器卸载应用 " + r.app() + "？", "WebSphere", Messages.getQuestionIcon()) == Messages.YES) {
            r.handler().session().uninstallApp(r.app());
        }
    }

    private AnAction appAction(String text, Icon icon, Consumer<Row> action) {
        return new DumbAwareAction(text, text, icon) {
            @Override
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(AnActionEvent e) {
                e.getPresentation().setEnabled(selected() != null);
            }

            @Override
            public void actionPerformed(AnActionEvent e) {
                Row r = selected();
                if (r != null) {
                    action.accept(r);
                }
            }
        };
    }

    private Row selected() {
        int i = table.getSelectedRow();
        return i >= 0 && i < model.rows.size() ? model.rows.get(i) : null;
    }

    private void refresh() {
        if (project.isDisposed()) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (WasSessionProcessHandler h : WasUpdates.running(project)) {
            for (Map.Entry<String, AppState> e : h.session().appStates().entrySet()) {
                rows.add(new Row(h, e.getKey(), e.getValue()));
            }
        }
        model.set(rows);
        if (!rows.isEmpty()) {
            model.fireTableRowsUpdated(0, rows.size() - 1);
        }
    }

    @Override
    public void dispose() {
        timer.stop();
    }
}
