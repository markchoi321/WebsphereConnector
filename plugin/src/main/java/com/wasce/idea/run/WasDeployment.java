package com.wasce.idea.run;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行配置中的一个部署项。source 是 Maven 模块的 artifactId，也可以是已构建好的归档路径。
 */
public final class WasDeployment {
    public String source = "";
    public String appName = "";
    public String contextRoot = "";
    public String goals = "package -DskipTests";
    public boolean deployEjb;

    public WasDeployment() {
    }

    public WasDeployment(String source, String appName, String contextRoot, String goals, boolean deployEjb) {
        this.source = source;
        this.appName = appName;
        this.contextRoot = contextRoot;
        this.goals = goals;
        this.deployEjb = deployEjb;
    }

    /** 持久化格式：每项一行，字段以制表符分隔 */
    public static String encode(List<WasDeployment> list) {
        StringBuilder sb = new StringBuilder();
        for (WasDeployment d : list) {
            sb.append(d.source).append('\t').append(d.appName).append('\t').append(d.contextRoot).append('\t')
                    .append(d.goals).append('\t').append(d.deployEjb).append('\n');
        }
        return sb.toString();
    }

    public static List<WasDeployment> decode(String text) {
        List<WasDeployment> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 5) {
                result.add(new WasDeployment(f[0], f[1], f[2], f[3], Boolean.parseBoolean(f[4])));
            }
        }
        return result;
    }
}
