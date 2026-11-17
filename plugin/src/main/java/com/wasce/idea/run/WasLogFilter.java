package com.wasce.idea.run;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.BrowserUtil;
import com.wasce.core.deploy.LogLine;

import java.util.ArrayList;
import java.util.List;

/**
 * WAS 日志高亮：错误和警告着色，消息 ID 可点击搜索 IBM 文档。
 */
final class WasLogFilter implements Filter {
    @Override
    public Result applyFilter(String line, int entireLength) {
        LogLine.Parsed p = LogLine.parse(line);
        if (p == null) {
            return null;
        }
        int lineStart = entireLength - line.length();
        int lineEnd = entireLength - (line.endsWith("\n") ? 1 : 0);
        List<ResultItem> items = new ArrayList<>();
        if (p.level() == 'E' || p.level() == 'F') {
            items.add(new ResultItem(lineStart, lineEnd, null, ConsoleViewContentType.ERROR_OUTPUT.getAttributes()));
        } else if (p.level() == 'W') {
            items.add(new ResultItem(lineStart, lineEnd, null, ConsoleViewContentType.LOG_WARNING_OUTPUT.getAttributes()));
        }
        if (p.messageId() != null) {
            String url = "https://www.ibm.com/search?q=" + p.messageId();
            HyperlinkInfo link = project -> BrowserUtil.browse(url);
            items.add(new ResultItem(lineStart + p.start(), lineStart + p.end(), link));
        }
        return items.isEmpty() ? null : new Result(items);
    }
}
