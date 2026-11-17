package com.wasce.idea.settings;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import com.wasce.core.install.WasHome;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.DefaultListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 设置页：Settings | Tools | WebSphere Servers。
 */
public final class WasInstallationConfigurable implements Configurable {
    private final CollectionListModel<WasInstallationManager.Entry> model = new CollectionListModel<>();
    private JPanel panel;

    @Override
    public String getDisplayName() {
        return "WebSphere Servers";
    }

    @Override
    public JComponent createComponent() {
        JBList<WasInstallationManager.Entry> list = new JBList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                WasInstallationManager.Entry e = (WasInstallationManager.Entry) value;
                return super.getListCellRendererComponent(l, e.name + "    " + e.home, index, selected, focus);
            }
        });
        list.getEmptyText().setText("尚未登记 WAS 安装，点击 + 选择 WAS 安装目录（WAS_HOME）");
        panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(ToolbarDecorator.createDecorator(list)
                .setAddAction(b -> addInstallation())
                .disableUpDownActions()
                .createPanel(), BorderLayout.CENTER);
        panel.add(new JBLabel("开发时可登记 fake-was/build/fake-was（执行 ./gradlew :fake-was:prepareFakeWas 生成）作为模拟环境"),
                BorderLayout.SOUTH);
        reset();
        return panel;
    }

    private void addInstallation() {
        VirtualFile dir = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("选择 WAS 安装目录（WAS_HOME）"), panel, null, null);
        if (dir == null) {
            return;
        }
        WasHome home = new WasHome(Path.of(dir.getPath()));
        String error = home.validate();
        if (error != null) {
            Messages.showErrorDialog(panel, error, "无效的 WAS 安装");
            return;
        }
        String base = "WAS " + home.version() + (home.isFake() ? "（模拟）" : "");
        String name = base;
        for (int i = 2; nameExists(name); i++) {
            name = base + " (" + i + ")";
        }
        model.add(new WasInstallationManager.Entry(name, home.path().toString()));
    }

    private boolean nameExists(String name) {
        return model.getItems().stream().anyMatch(e -> e.name.equals(name));
    }

    @Override
    public boolean isModified() {
        List<WasInstallationManager.Entry> saved = WasInstallationManager.getInstance().list();
        List<WasInstallationManager.Entry> current = model.getItems();
        if (saved.size() != current.size()) {
            return true;
        }
        for (int i = 0; i < saved.size(); i++) {
            if (!Objects.equals(saved.get(i).name, current.get(i).name) || !Objects.equals(saved.get(i).home, current.get(i).home)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() {
        WasInstallationManager.getInstance().setAll(model.getItems());
    }

    @Override
    public void reset() {
        model.replaceAll(WasInstallationManager.getInstance().list());
    }
}
