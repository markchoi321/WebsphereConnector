package com.wasce.core.deploy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 产出归档的构建命令。
 */
public record BuildCommand(List<String> command, Path directory, Map<String, String> environment) {
}
