package com.wasce.core.deploy;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个部署项。
 *
 * @param appName            WAS 中的应用名
 * @param archive            构建产物（.ear / .war / .jar）
 * @param contextRoot        独立 WAR 的 context root，EAR 忽略
 * @param deployEjb          是否运行 deployejb（EJB 2.x 需要）
 * @param build              构建命令，null 表示归档已存在、无需构建
 * @param modules            工程模块，用于建立增量映射
 * @param incrementalBlocked 不为 null 时禁用增量更新，内容为原因（如 Web 资源过滤）
 */
public record DeployItem(String appName, Path archive, String contextRoot, boolean deployEjb, BuildCommand build,
                         List<ReactorModule> modules, String incrementalBlocked) {
    public boolean isEar() {
        return archive.getFileName().toString().endsWith(".ear");
    }
}
