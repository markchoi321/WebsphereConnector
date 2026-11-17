package com.wasce.idea.run;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.wasce.core.deploy.DeployHistory;

import java.util.Map;
import java.util.TreeMap;

/**
 * 部署指纹，保存在 workspace 中，属于本机信息，不进版本库。
 */
@Service(Service.Level.PROJECT)
@State(name = "WasCeDeployHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class WasDeployHistory implements PersistentStateComponent<WasDeployHistory.HistoryState>, DeployHistory {

    public static final class HistoryState {
        public Map<String, String> fingerprints = new TreeMap<>();
    }

    private HistoryState state = new HistoryState();

    public static WasDeployHistory getInstance(Project project) {
        return project.getService(WasDeployHistory.class);
    }

    @Override
    public synchronized HistoryState getState() {
        return state;
    }

    @Override
    public synchronized void loadState(HistoryState loaded) {
        state = loaded;
    }

    @Override
    public synchronized String fingerprint(String app) {
        return state.fingerprints.get(app);
    }

    @Override
    public synchronized void record(String app, String fingerprint) {
        if (fingerprint == null) {
            state.fingerprints.remove(app);
        } else {
            state.fingerprints.put(app, fingerprint);
        }
    }
}
