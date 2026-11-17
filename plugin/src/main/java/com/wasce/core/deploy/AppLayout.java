package com.wasce.core.deploy;

import java.util.List;

/**
 * 从实际归档得到的应用布局。
 *
 * @param roots       增量映射
 * @param warnings    未能匹配到源码的模块等提示
 * @param contextRoot 第一个 Web 模块的 context root，没有时为 null
 */
public record AppLayout(List<SyncRoot> roots, List<String> warnings, String contextRoot) {
}
