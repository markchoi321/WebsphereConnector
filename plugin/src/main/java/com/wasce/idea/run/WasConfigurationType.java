package com.wasce.idea.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.wasce.core.server.ServerSpec;

import javax.swing.Icon;

/**
 * 运行配置类型 "WebSphere Server (CE)"，包含本地与远程两个 factory。
 */
public final class WasConfigurationType extends ConfigurationTypeBase {
    public static final String ID = "WasCeServer";
    public static final Icon ICON = IconLoader.getIcon("/icons/websphere.svg", WasConfigurationType.class);

    public WasConfigurationType() {
        super(ID, "WebSphere Server (CE)", "IBM WebSphere Application Server 8.5：本地启停与调试、远程连接", ICON);
        addFactory(new Factory(this, ServerSpec.Mode.LOCAL));
        addFactory(new Factory(this, ServerSpec.Mode.REMOTE));
    }

    public static WasConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(WasConfigurationType.class);
    }

    static final class Factory extends ConfigurationFactory {
        private final ServerSpec.Mode mode;

        Factory(WasConfigurationType type, ServerSpec.Mode mode) {
            super(type);
            this.mode = mode;
        }

        @Override
        public String getId() {
            return mode == ServerSpec.Mode.LOCAL ? "Local" : "Remote";
        }

        @Override
        public String getName() {
            return mode == ServerSpec.Mode.LOCAL ? "本地" : "远程";
        }

        @Override
        public RunConfiguration createTemplateConfiguration(Project project) {
            WasRunConfiguration c = new WasRunConfiguration(project, this, "WebSphere");
            c.getOptions().setMode(mode.name());
            return c;
        }

        @Override
        public Class<? extends BaseState> getOptionsClass() {
            return WasRunConfigurationOptions.class;
        }
    }
}
