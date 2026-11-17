package com.wasce.core.deploy;

import java.nio.file.Path;

/**
 * 增量映射的一条根：source 下的文件对应已部署目录下 target 路径。
 * JAR 类型比较特殊：source 是模块的类输出目录，target 是要重新打包的 jar。
 */
public record SyncRoot(Path source, String target, Kind kind) {
    public enum Kind {CLASSES, WEB, EJB, JAR}
}
