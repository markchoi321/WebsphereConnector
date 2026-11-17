package com.wasce.idea.run;

import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider;
import com.intellij.openapi.project.Project;

import java.util.Collection;
import java.util.List;

/**
 * 让 WebSphere 运行配置默认出现在 Services 视图中。
 */
public final class WasRunDashboardTypes implements RunDashboardDefaultTypesProvider {
    @Override
    public Collection<String> getDefaultTypeIds(Project project) {
        return List.of(WasConfigurationType.ID);
    }
}
