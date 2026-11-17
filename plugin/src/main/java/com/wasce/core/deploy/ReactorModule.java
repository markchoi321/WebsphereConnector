package com.wasce.core.deploy;

import java.nio.file.Path;
import java.util.List;

/**
 * Maven 工程中的一个模块，用于把归档内的条目匹配回源码目录。
 *
 * @param id           模块标识（artifactId）
 * @param archiveNames 可能的归档文件名（不含扩展名），如 artifactId-version、finalName、bundleFileName
 * @param classesDir   编译输出目录
 * @param webappDir    WAR 的 Web 源目录，非 WAR 为 null
 */
public record ReactorModule(String id, List<String> archiveNames, Path classesDir, Path webappDir) {
}
