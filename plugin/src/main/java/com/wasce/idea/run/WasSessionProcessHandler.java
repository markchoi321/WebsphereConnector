package com.wasce.idea.run;

import com.intellij.debugger.DebuggerManager;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.HotSwapStatusListener;
import com.intellij.debugger.ui.HotSwapUI;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.wasce.core.server.ServerListener;
import com.wasce.core.server.ServerSession;
import com.wasce.core.server.ServerSpec;
import com.wasce.core.server.ServerState;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 ProcessHandler 表示一次服务器会话：
 * 停止按钮只把"停止"放入会话队列，服务器真正停止后才通知终止，IDE 显示的就是真实状态。
 */
public final class WasSessionProcessHandler extends ProcessHandler implements ServerListener {
    private final ServerSession session;
    private final ServerSpec spec;
    private final Project project;
    private final String displayName;
    private final String frameUpdate;
    private final boolean openBrowser;
    private final Map<String, String> appUrls = new ConcurrentHashMap<>();

    WasSessionProcessHandler(ServerSpec spec, Project project, String displayName, String frameUpdate, boolean openBrowser) {
        this.spec = spec;
        this.project = project;
        this.displayName = displayName;
        this.frameUpdate = frameUpdate;
        this.openBrowser = openBrowser;
        this.session = new ServerSession(spec, this);
    }

    public ServerSession session() {
        return session;
    }

    public String displayName() {
        return displayName;
    }

    public String frameUpdate() {
        return frameUpdate;
    }

    /** 应用访问地址，尚未部署成功时为 null */
    public String appUrl(String app) {
        return appUrls.get(app);
    }

    @Override
    public void startNotify() {
        super.startNotify();
        session.start();
    }

    /**
     * 不调用 super：平台的 destroyProcess 会先广播 willBeDestroyed=true，
     * 调试器（DebuggerManagerImpl）收到后直接 VM.exit(-1) 强杀服务器 JVM，跳过正常停止。
     * 这里先让调试器正常断开（恢复所有线程），再把停止交给会话队列；停止完成后由 onEnded 通知终止。
     */
    @Override
    public void destroyProcess() {
        if (isProcessTerminated()) {
            return;
        }
        DebugProcess debugProcess = DebuggerManager.getInstance(project).getDebugProcess(this);
        if (debugProcess != null) {
            debugProcess.stop(false);
        }
        destroyProcessImpl();
    }

    @Override
    protected void destroyProcessImpl() {
        if (spec.mode() == ServerSpec.Mode.LOCAL && session.isExternal() && ApplicationManager.getApplication().isDispatchThread()) {
            int choice = Messages.showYesNoDialog(project,
                    "该服务器不是由本次会话启动的。要停止服务器，还是只断开连接？",
                    "WebSphere", "停止服务器", "只断开", Messages.getQuestionIcon());
            if (choice != Messages.YES) {
                session.detach();
                return;
            }
        }
        session.stop();
    }

    @Override
    protected void detachProcessImpl() {
        session.detach();
    }

    @Override
    public boolean detachIsDefault() {
        return spec.mode() == ServerSpec.Mode.REMOTE;
    }

    @Override
    public OutputStream getProcessInput() {
        return null;
    }

    // ---------------------------------------------------------------- ServerListener

    @Override
    public void onText(String text, Output kind) {
        Key<?> type = switch (kind) {
            case SYSTEM -> ProcessOutputTypes.SYSTEM;
            case STDOUT -> ProcessOutputTypes.STDOUT;
            case STDERR -> ProcessOutputTypes.STDERR;
        };
        notifyTextAvailable(text, type);
    }

    @Override
    public void onStateChanged(ServerState state) {
        notifyTextAvailable("[状态] " + state.label() + "\n", ProcessOutputTypes.SYSTEM);
    }

    @Override
    public boolean confirmAttach(String message) {
        AtomicBoolean result = new AtomicBoolean();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(
                Messages.showYesNoDialog(project, message, "WebSphere", "连接", "取消", Messages.getQuestionIcon()) == Messages.YES),
                ModalityState.defaultModalityState());
        return result.get();
    }

    @Override
    public void onAppDeployed(String app, String url) {
        if (url == null) {
            return;
        }
        boolean first = appUrls.put(app, url) == null;
        if (first && openBrowser) {
            BrowserUtil.browse(url);
        }
    }

    @Override
    public void onOperationFailed(String message) {
        WasUpdates.notify(project, message, NotificationType.ERROR);
    }

    /** 在 EDT 上发起 HotSwap 并等待结果。未收到回调时按成功处理：231 在没有可重载的类时不回调 */
    @Override
    public boolean hotSwap() {
        DebuggerManagerEx manager = DebuggerManagerEx.getInstanceEx(project);
        DebugProcess process = manager.getDebugProcess(this);
        DebuggerSession debugger = process == null ? null : manager.getSession(process);
        if (debugger == null) {
            return false;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        HotSwapStatusListener callback = new HotSwapStatusListener() {
            @Override
            public void onSuccess(List<DebuggerSession> sessions) {
                result.complete(true);
            }

            @Override
            public void onFailure(List<DebuggerSession> sessions) {
                result.complete(false);
            }

            @Override
            public void onCancel(List<DebuggerSession> sessions) {
                result.complete(false);
            }

            // 252 新增的回调，231 中不存在，因此不加 @Override
            public void onNothingToReload(List<DebuggerSession> sessions) {
                result.complete(true);
            }
        };
        ApplicationManager.getApplication().invokeLater(
                () -> HotSwapUI.getInstance(project).reloadChangedClasses(debugger, false, callback), ModalityState.any());
        try {
            return result.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    @Override
    public void onEnded(boolean detached, int exitCode) {
        if (detached) {
            notifyProcessDetached();
        } else {
            notifyProcessTerminated(exitCode);
        }
    }
}
