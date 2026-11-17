package com.wasce.core.deploy;

import java.util.ArrayList;
import java.util.List;

/**
 * 按改动类型选择代价最小的更新方式（设计方案 9.2）。
 */
public final class UpdatePlanner {
    private UpdatePlanner() {
    }

    public enum Mode {RESOURCES, CLASSES_AND_RESOURCES, REDEPLOY}

    public enum Action {NOTHING, SYNC_ONLY, HOTSWAP, RESTART_APP, REDEPLOY}

    public enum Kind {STATIC, CLASS, CLASSPATH_RESOURCE, DESCRIPTOR, JAR}

    /**
     * @param action 要执行的动作
     * @param apply  本次要应用的改动，未包含的改动留待下次
     * @param reason 给用户看的说明
     */
    public record Plan(Action action, ChangeTracker.Changes apply, String reason) {
    }

    public static Kind kind(ChangeTracker.Entry e) {
        String target = e.target();
        String name = target.substring(target.lastIndexOf('/') + 1);
        if (e.root().kind() == SyncRoot.Kind.JAR) {
            return Kind.JAR;
        }
        if (isDescriptor(target, name)) {
            return Kind.DESCRIPTOR;
        }
        if (e.root().kind() == SyncRoot.Kind.WEB) {
            return Kind.STATIC;
        }
        return name.endsWith(".class") ? Kind.CLASS : Kind.CLASSPATH_RESOURCE;
    }

    private static boolean isDescriptor(String target, String name) {
        if (!target.contains("WEB-INF/") && !target.contains("META-INF/")) {
            return false;
        }
        return name.equals("web.xml") || name.equals("ejb-jar.xml") || name.equals("application.xml")
               || name.equals("persistence.xml") || name.equals("beans.xml") || name.equals("web-fragment.xml")
               || name.equals("faces-config.xml") || name.startsWith("ibm-") || name.equals("MANIFEST.MF");
    }

    public static Plan plan(ChangeTracker.Changes changes, Mode mode, boolean debugging, String incrementalBlocked) {
        if (mode == Mode.REDEPLOY) {
            return new Plan(Action.REDEPLOY, changes, "请求重新部署");
        }
        if (changes.isEmpty()) {
            return new Plan(Action.NOTHING, changes, "没有改动");
        }
        if (incrementalBlocked != null) {
            return new Plan(Action.REDEPLOY, changes, incrementalBlocked);
        }
        List<ChangeTracker.Entry> changed = changes.changed();
        List<ChangeTracker.Entry> deleted = changes.deleted();
        if (mode == Mode.RESOURCES) {
            changed = filterStatic(changed);
            deleted = filterStatic(deleted);
            ChangeTracker.Changes apply = new ChangeTracker.Changes(changed, deleted);
            return apply.isEmpty() ? new Plan(Action.NOTHING, apply, "没有资源改动")
                    : new Plan(Action.SYNC_ONLY, apply, "只有静态资源");
        }
        boolean descriptor = false;
        boolean restart = false;
        boolean clazz = false;
        for (ChangeTracker.Entry e : changed) {
            Kind k = kind(e);
            descriptor |= k == Kind.DESCRIPTOR;
            restart |= k == Kind.JAR || k == Kind.CLASSPATH_RESOURCE;
            clazz |= k == Kind.CLASS;
        }
        for (ChangeTracker.Entry e : deleted) {
            Kind k = kind(e);
            descriptor |= k == Kind.DESCRIPTOR;
            restart |= k != Kind.STATIC;
        }
        if (descriptor) {
            return new Plan(Action.REDEPLOY, changes, "部署描述符发生变化");
        }
        if (restart) {
            return new Plan(Action.RESTART_APP, changes, "配置、依赖模块或类被删除，需要重启应用");
        }
        if (clazz) {
            return debugging ? new Plan(Action.HOTSWAP, changes, "类发生变化，尝试 HotSwap")
                    : new Plan(Action.RESTART_APP, changes, "类发生变化，需要重启应用");
        }
        return new Plan(Action.SYNC_ONLY, changes, "只有静态资源");
    }

    private static List<ChangeTracker.Entry> filterStatic(List<ChangeTracker.Entry> list) {
        List<ChangeTracker.Entry> r = new ArrayList<>();
        for (ChangeTracker.Entry e : list) {
            if (kind(e) == Kind.STATIC) {
                r.add(e);
            }
        }
        return r;
    }
}
