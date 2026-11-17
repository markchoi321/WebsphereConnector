package com.wasce.idea.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;

import java.util.ArrayList;
import java.util.List;

/**
 * WAS 安装登记（应用级，所有工程共享）。
 */
@Service(Service.Level.APP)
@State(name = "WasCeInstallations", storages = @Storage("wasce-installations.xml"))
public final class WasInstallationManager implements PersistentStateComponent<WasInstallationManager.InstallationsState> {

    public static final class InstallationsState {
        public List<Entry> installations = new ArrayList<>();
    }

    public static final class Entry {
        public String name = "";
        public String home = "";

        public Entry() {
        }

        public Entry(String name, String home) {
            this.name = name;
            this.home = home;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private InstallationsState state = new InstallationsState();

    public static WasInstallationManager getInstance() {
        return ApplicationManager.getApplication().getService(WasInstallationManager.class);
    }

    @Override
    public InstallationsState getState() {
        return state;
    }

    @Override
    public void loadState(InstallationsState loaded) {
        state = loaded;
    }

    public synchronized List<Entry> list() {
        List<Entry> copy = new ArrayList<>();
        for (Entry e : state.installations) {
            copy.add(new Entry(e.name, e.home));
        }
        return copy;
    }

    public synchronized Entry find(String name) {
        for (Entry e : state.installations) {
            if (e.name.equals(name)) {
                return new Entry(e.name, e.home);
            }
        }
        return null;
    }

    public synchronized void setAll(List<Entry> entries) {
        state.installations = new ArrayList<>(entries);
    }
}
