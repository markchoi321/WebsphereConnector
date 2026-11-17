package com.wasce.idea.run;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import com.wasce.core.agent.AgentCallException;
import com.wasce.core.deploy.TrustStores;
import com.wasce.core.install.WasHome;
import com.wasce.core.profile.ProfileReader;
import com.wasce.core.server.ServerSession;
import com.wasce.core.server.ServerSpec;
import com.wasce.idea.settings.WasInstallationManager;
import com.wasce.idea.util.WasCredentials;
import com.wasce.protocol.Protocol;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行配置编辑器。本地与远程共用，按模式显示对应字段。
 * 密码在后台线程读写 PasswordSafe，避免阻塞 EDT。
 */
final class WasSettingsEditor extends SettingsEditor<WasRunConfiguration> {
    private static final String[] POLICY_VALUES = {"ASK", "ATTACH", "FAIL"};
    private static final String[] POLICY_LABELS = {"询问", "直接连接", "报错"};
    private static final String[] FRAME_VALUES = {"NONE", "RESOURCES", "CLASSES"};
    private static final String[] FRAME_LABELS = {"不更新", "更新资源", "更新类和资源"};

    private abstract static class Column<T> extends ColumnInfo<WasDeployment, T> {
        Column(String name) {
            super(name);
        }

        @Override
        public boolean isCellEditable(WasDeployment item) {
            return true;
        }
    }

    private static final Column<String> SOURCE = new Column<>("模块或归档") {
        @Override
        public String valueOf(WasDeployment d) {
            return d.source;
        }

        @Override
        public boolean isCellEditable(WasDeployment item) {
            return false;
        }
    };
    private static final Column<String> APP = new Column<>("应用名") {
        @Override
        public String valueOf(WasDeployment d) {
            return d.appName;
        }

        @Override
        public void setValue(WasDeployment d, String v) {
            d.appName = v.trim();
        }
    };
    private static final Column<String> CONTEXT = new Column<>("Context Root（仅独立 WAR）") {
        @Override
        public String valueOf(WasDeployment d) {
            return d.contextRoot;
        }

        @Override
        public void setValue(WasDeployment d, String v) {
            d.contextRoot = v.trim();
        }
    };
    private static final Column<String> GOALS = new Column<>("Maven 目标") {
        @Override
        public String valueOf(WasDeployment d) {
            return d.goals;
        }

        @Override
        public void setValue(WasDeployment d, String v) {
            d.goals = v.trim();
        }
    };
    private static final Column<Boolean> EJB = new Column<>("deployejb") {
        @Override
        public Boolean valueOf(WasDeployment d) {
            return d.deployEjb;
        }

        @Override
        public void setValue(WasDeployment d, Boolean v) {
            d.deployEjb = v;
        }

        @Override
        public Class<?> getColumnClass() {
            return Boolean.class;
        }
    };

    private final Project project;
    private final ComboBox<String> installationCombo = new ComboBox<>();
    private final TextFieldWithBrowseButton profileField = new TextFieldWithBrowseButton();
    private final ComboBox<String> serverCombo = new ComboBox<>();
    private final JBTextField hostField = new JBTextField();
    private final JBIntSpinner soapPortSpinner = new JBIntSpinner(8880, 1, 65535);
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBIntSpinner debugPortSpinner = new JBIntSpinner(7777, 1, 65535);
    private final RawCommandLineEditor vmOptionsField = new RawCommandLineEditor();
    private final JBIntSpinner startTimeoutSpinner = new JBIntSpinner(600, 10, 7200);
    private final JBIntSpinner stopTimeoutSpinner = new JBIntSpinner(120, 5, 3600);
    private final ComboBox<String> policyCombo = new ComboBox<>(POLICY_LABELS);
    private final ComboBox<String> frameCombo = new ComboBox<>(FRAME_LABELS);
    private final JBTextField mavenField = new JBTextField();
    private final JBTextField encodingField = new JBTextField();
    private final JBCheckBox browserCheck = new JBCheckBox("部署成功后在浏览器中打开");
    private final ListTableModel<WasDeployment> deployModel = new ListTableModel<>(SOURCE, APP, CONTEXT, GOALS, EJB);
    private final TableView<WasDeployment> deployTable = new TableView<>(deployModel);
    private final JButton testButton = new JButton("Test Connection");

    private final JBLabel profileLabel = new JBLabel("Profile 目录:");
    private final JBLabel serverLabel = new JBLabel("服务器:");
    private final JBLabel policyLabel = new JBLabel("服务器已运行时:");
    private final JBLabel hostLabel = new JBLabel("主机:");
    private final JBLabel soapPortLabel = new JBLabel("SOAP 端口:");
    private final JBLabel infoLabel = new JBLabel();

    /** 当前编辑器实例使用的凭据 key，保证多次 apply 到副本时 key 一致 */
    private String credentialKey = "";
    /** 已保存到 PasswordSafe 的密码，用于判断是否需要重新保存 */
    private String savedPassword = "";
    private boolean passwordLoaded;
    private WasRunConfiguration current;

    WasSettingsEditor(Project project) {
        this.project = project;
        // 不使用 addBrowseFolderListener：其 231 版签名在 252 中已计划移除，新签名在 231 中不存在
        profileField.addActionListener(e -> {
            VirtualFile dir = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("选择 profile 目录"), profileField, null, null);
            if (dir != null) {
                profileField.setText(dir.getPath());
            }
        });
        profileField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                refreshServers();
            }
        });
        installationCombo.addActionListener(e -> onInstallationChanged());
        passwordField.getEmptyText().setText("未保存");
        testButton.addActionListener(e -> testConnection());
        mavenField.getEmptyText().setText("默认依次查找 mvnw、PATH、IDEA 自带 Maven");
        encodingField.getEmptyText().setText("默认使用系统编码");
    }

    @Override
    protected JComponent createEditor() {
        JPanel deployPanel = ToolbarDecorator.createDecorator(deployTable)
                .setAddAction(b -> addDeployment())
                .setRemoveAction(b -> removeDeployment())
                .disableUpDownActions()
                .setPreferredSize(new Dimension(JBUI.scale(600), JBUI.scale(110)))
                .createPanel();
        JPanel testRow = new JPanel(new BorderLayout());
        testRow.add(testButton, BorderLayout.WEST);
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("WAS 安装:", installationCombo)
                .addLabeledComponent(profileLabel, profileField)
                .addLabeledComponent(serverLabel, serverCombo)
                .addLabeledComponent(hostLabel, hostField)
                .addLabeledComponent(soapPortLabel, soapPortSpinner)
                .addComponent(infoLabel)
                .addLabeledComponent("管理用户名:", userField)
                .addLabeledComponent("密码:", passwordField)
                .addTooltip("未开启全局安全时留空；密码保存在 IDE 密码库中，不写入运行配置文件")
                .addComponent(testRow)
                .addSeparator()
                .addLabeledComponentFillVertically("部署:", deployPanel)
                .addLabeledComponent("Maven 可执行文件:", mavenField)
                .addComponent(browserCheck)
                .addLabeledComponent("窗口失去焦点时:", frameCombo)
                .addSeparator()
                .addLabeledComponent("调试端口:", debugPortSpinner)
                .addLabeledComponent("附加 JVM 参数:", vmOptionsField)
                .addTooltip("仅对本次启动生效，注入到生成的启动脚本中，不修改 server.xml")
                .addLabeledComponent("日志编码:", encodingField)
                .addLabeledComponent("启动超时（秒）:", startTimeoutSpinner)
                .addLabeledComponent("停止超时（秒）:", stopTimeoutSpinner)
                .addLabeledComponent(policyLabel, policyCombo)
                .getPanel();
    }

    private void addDeployment() {
        List<String> modules = new ArrayList<>(MavenDeployResolver.deployableModules(project));
        modules.removeIf(m -> deployModel.getItems().stream().anyMatch(d -> d.source.equals(m)));
        String archiveChoice = "选择已构建的归档文件…";
        modules.add(archiveChoice);
        JBPopupFactory.getInstance().createPopupChooserBuilder(modules)
                .setTitle("添加部署项")
                .setItemChosenCallback(choice -> {
                    if (choice.equals(archiveChoice)) {
                        VirtualFile f = FileChooser.chooseFile(new FileChooserDescriptor(true, false, true, true, false, false)
                                .withTitle("选择 EAR 或 WAR"), deployTable, project, null);
                        if (f != null) {
                            addRow(f.getPath(), f.getNameWithoutExtension(), "war".equals(f.getExtension()));
                        }
                    } else {
                        addRow(choice, choice, MavenDeployResolver.isWar(project, choice));
                    }
                })
                .createPopup()
                .showInCenterOf(deployTable);
    }

    private void addRow(String source, String app, boolean war) {
        deployModel.addRow(new WasDeployment(source, app, war ? "/" + app : "", "package -DskipTests", false));
        fireEditorStateChanged();
    }

    private void removeDeployment() {
        int row = deployTable.getSelectedRow();
        if (row >= 0) {
            deployModel.removeRow(deployTable.convertRowIndexToModel(row));
            fireEditorStateChanged();
        }
    }

    @Override
    protected void resetEditorFrom(WasRunConfiguration c) {
        current = c;
        WasRunConfigurationOptions o = c.getOptions();
        boolean remote = c.mode() == ServerSpec.Mode.REMOTE;
        for (JComponent comp : List.of(profileLabel, profileField, serverLabel, serverCombo, policyLabel, policyCombo)) {
            comp.setVisible(!remote);
        }
        for (JComponent comp : List.of(hostLabel, hostField, soapPortLabel, soapPortSpinner)) {
            comp.setVisible(remote);
        }

        installationCombo.removeAllItems();
        WasInstallationManager.getInstance().list().forEach(e -> installationCombo.addItem(e.name));
        installationCombo.setSelectedItem(o.getInstallation());
        profileField.setText(o.getProfilePath());
        refreshServers();
        serverCombo.setSelectedItem(o.getServerId());
        hostField.setText(o.getHost());
        soapPortSpinner.setNumber(o.getSoapPort());
        userField.setText(o.getUser());
        debugPortSpinner.setNumber(o.getDebugPort());
        vmOptionsField.setText(o.getVmOptions());
        startTimeoutSpinner.setNumber(o.getStartTimeout());
        stopTimeoutSpinner.setNumber(o.getStopTimeout());
        policyCombo.setSelectedIndex(Math.max(0, List.of(POLICY_VALUES).indexOf(o.getWhenRunning())));
        frameCombo.setSelectedIndex(Math.max(0, List.of(FRAME_VALUES).indexOf(o.getFrameUpdate())));
        mavenField.setText(o.getMavenExecutable());
        encodingField.setText(o.getLogEncoding());
        browserCheck.setSelected(o.isOpenBrowser());
        deployModel.setItems(new ArrayList<>(WasDeployment.decode(o.getDeployments())));

        credentialKey = o.getCredentialKey();
        if (!passwordLoaded) {
            passwordLoaded = true;
            String key = credentialKey;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String p = WasCredentials.load(key);
                ApplicationManager.getApplication().invokeLater(() -> {
                    savedPassword = p == null ? "" : p;
                    if (passwordField.getPassword().length == 0) {
                        passwordField.setText(savedPassword);
                    }
                }, ModalityState.any());
            });
        }
    }

    @Override
    protected void applyEditorTo(WasRunConfiguration c) {
        if (deployTable.isEditing()) {
            deployTable.getCellEditor().stopCellEditing();
        }
        WasRunConfigurationOptions o = c.getOptions();
        o.setInstallation(Objects.toString(installationCombo.getSelectedItem(), ""));
        o.setProfilePath(profileField.getText().trim());
        o.setServerId(Objects.toString(serverCombo.getSelectedItem(), ""));
        o.setHost(hostField.getText().trim());
        o.setSoapPort(soapPortSpinner.getNumber());
        o.setUser(userField.getText().trim());
        o.setDebugPort(debugPortSpinner.getNumber());
        o.setVmOptions(vmOptionsField.getText());
        o.setStartTimeout(startTimeoutSpinner.getNumber());
        o.setStopTimeout(stopTimeoutSpinner.getNumber());
        o.setWhenRunning(POLICY_VALUES[Math.max(0, policyCombo.getSelectedIndex())]);
        o.setFrameUpdate(FRAME_VALUES[Math.max(0, frameCombo.getSelectedIndex())]);
        o.setMavenExecutable(mavenField.getText().trim());
        o.setLogEncoding(encodingField.getText().trim());
        o.setOpenBrowser(browserCheck.isSelected());
        o.setDeployments(WasDeployment.encode(deployModel.getItems()));

        String password = new String(passwordField.getPassword());
        if (passwordLoaded && !password.equals(savedPassword)) {
            if (credentialKey.isEmpty()) {
                credentialKey = UUID.randomUUID().toString();
            }
            savedPassword = password;
            String key = credentialKey;
            String user = o.getUser();
            ApplicationManager.getApplication().executeOnPooledThread(() -> WasCredentials.save(key, user, password));
        }
        o.setCredentialKey(credentialKey);
    }

    /** 用编辑器当前内容在后台测试连接，不要求先保存 */
    private void testConnection() {
        if (current == null) {
            return;
        }
        WasRunConfiguration copy = (WasRunConfiguration) current.clone();
        applyEditorTo(copy);
        String password = new String(passwordField.getPassword());
        ServerSpec spec;
        try {
            copy.checkConfiguration();
            spec = new WasRunProfileState(copy, false).buildSpec(false, () -> password);
        } catch (Exception e) {
            Messages.showErrorDialog(testButton, e.getMessage(), "Test Connection");
            return;
        }
        ProgressManager.getInstance().run(new Task.Modal(project, testButton, "连接 WebSphere", true) {
            private Map<String, String> info;
            private AgentCallException failure;
            private String error;

            @Override
            public void run(ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    info = ServerSession.testConnection(spec);
                } catch (AgentCallException e) {
                    failure = e;
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }

            @Override
            public void onFinished() {
                if (info != null) {
                    Messages.showInfoMessage(testButton, "连接成功\n服务器: " + info.get(Protocol.KEY_CELL) + "/"
                            + info.get(Protocol.KEY_NODE) + "/" + info.get(Protocol.KEY_SERVER)
                            + "\n状态: " + info.get(Protocol.KEY_STATE), "Test Connection");
                } else if (failure != null && Protocol.ERR_SSL.equals(failure.getCode()) && spec.mode() == ServerSpec.Mode.REMOTE) {
                    offerTrust(spec);
                } else {
                    Messages.showErrorDialog(testButton, failure != null ? ServerSession.describe(failure) : error, "Test Connection");
                }
            }
        });
    }

    /** SSL 失败时展示证书指纹，用户确认后导入自管信任库 */
    private void offerTrust(ServerSpec spec) {
        ProgressManager.getInstance().run(new Task.Modal(project, testButton, "读取服务器证书", true) {
            private X509Certificate cert;
            private String fingerprint;
            private String error;

            @Override
            public void run(ProgressIndicator indicator) {
                try {
                    cert = TrustStores.fetchCertificate(spec.host(), spec.soapPort());
                    fingerprint = TrustStores.fingerprint(cert);
                } catch (Exception e) {
                    error = e.toString();
                }
            }

            @Override
            public void onFinished() {
                if (cert == null) {
                    Messages.showErrorDialog(testButton, "SSL 握手失败，且无法读取服务器证书: " + error, "Test Connection");
                    return;
                }
                int choice = Messages.showYesNoDialog(testButton, "服务器证书不受信任。\n\n主题: " + cert.getSubjectX500Principal().getName()
                        + "\nSHA-256: " + fingerprint + "\n\n请与服务器管理员核对指纹。确认信任并导入？", "信任服务器证书",
                        Messages.getWarningIcon());
                if (choice != Messages.YES) {
                    return;
                }
                try {
                    TrustStores.importCertificate(spec.trustStore(), spec.host() + "_" + spec.soapPort(), cert);
                    Messages.showInfoMessage(testButton, "证书已导入，请重新测试连接", "Test Connection");
                } catch (Exception e) {
                    Messages.showErrorDialog(testButton, "导入失败: " + e.getMessage(), "Test Connection");
                }
            }
        });
    }

    private void onInstallationChanged() {
        WasInstallationManager.Entry entry = WasInstallationManager.getInstance().find(Objects.toString(installationCombo.getSelectedItem(), ""));
        if (entry == null) {
            infoLabel.setText("");
            return;
        }
        WasHome home = new WasHome(Path.of(entry.home));
        String error = home.validate();
        infoLabel.setText(error != null ? error : "版本 " + home.version() + (home.isFake() ? "（模拟环境）" : ""));
        if (profileField.isVisible() && profileField.getText().isBlank() && !home.profiles().isEmpty()) {
            profileField.setText(home.profiles().get(0).toString());
        }
    }

    private void refreshServers() {
        Object selected = serverCombo.getSelectedItem();
        serverCombo.removeAllItems();
        String path = profileField.getText().trim();
        if (path.isEmpty()) {
            return;
        }
        try {
            for (ProfileReader.ServerId id : ProfileReader.listServers(Path.of(path))) {
                serverCombo.addItem(id.toString());
            }
        } catch (Exception e) {
            // 目录无效时列表为空，由 checkConfiguration 给出提示
        }
        if (selected != null) {
            serverCombo.setSelectedItem(selected);
        }
        if (serverCombo.getSelectedItem() == null && serverCombo.getItemCount() > 0) {
            serverCombo.setSelectedIndex(0);
        }
    }
}
