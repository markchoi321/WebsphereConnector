package com.wasce.idea.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.execution.ParametersListUtil;
import com.wasce.core.deploy.BuildCommand;
import com.wasce.core.deploy.DeployItem;
import com.wasce.core.deploy.ReactorModule;
import org.jdom.Element;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把部署项解析为 DeployItem：从 IDEA 的 Maven 模型读取产物路径和模块目录，
 * 归档内部布局在构建后从实际归档读取（LayoutScanner）。
 */
final class MavenDeployResolver {
    private static final String PLUGINS = "org.apache.maven.plugins";

    private MavenDeployResolver() {
    }

    /** 可部署的 Maven 模块（packaging 为 ear 或 war） */
    static List<String> deployableModules(Project project) {
        List<String> result = new ArrayList<>();
        for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
            if ("ear".equals(p.getPackaging()) || "war".equals(p.getPackaging())) {
                result.add(p.getMavenId().getArtifactId());
            }
        }
        return result;
    }

    static boolean isWar(Project project, String artifactId) {
        MavenProject p = find(project, artifactId);
        return p != null && "war".equals(p.getPackaging());
    }

    static List<DeployItem> resolve(Project project, WasRunConfigurationOptions o) throws ExecutionException {
        List<DeployItem> items = new ArrayList<>();
        for (WasDeployment d : WasDeployment.decode(o.getDeployments())) {
            MavenProject mp = find(project, d.source);
            if (mp != null) {
                items.add(fromMaven(project, o, d, mp));
            } else if (Files.isRegularFile(Path.of(d.source))) {
                items.add(new DeployItem(d.appName, Path.of(d.source), d.contextRoot, d.deployEjb, null, List.of(),
                        "直接指定归档，没有源码映射"));
            } else {
                throw new ExecutionException("部署项 " + d.source + " 既不是 Maven 模块，也不是已存在的归档文件。"
                        + "如果刚修改过 pom.xml，请先重新加载 Maven 工程");
            }
        }
        return items;
    }

    private static MavenProject find(Project project, String artifactId) {
        for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
            if (p.getMavenId().getArtifactId() != null && p.getMavenId().getArtifactId().equals(artifactId)) {
                return p;
            }
        }
        return null;
    }

    private static DeployItem fromMaven(Project project, WasRunConfigurationOptions o, WasDeployment d, MavenProject mp)
            throws ExecutionException {
        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        Path archive = Path.of(mp.getBuildDirectory(), mp.getFinalName() + "." + mp.getPackaging());
        Map<String, List<String>> aliases = bundleAliases(mp);
        List<ReactorModule> modules = new ArrayList<>();
        String blocked = null;
        for (MavenProject m : manager.getProjects()) {
            String id = m.getMavenId().getArtifactId();
            String version = m.getMavenId().getVersion();
            // maven-ear-plugin 的 fileNameMapping 不同取值对应的文件名，full 带 groupId 前缀
            List<String> names = new ArrayList<>(List.of(id + "-" + version, id, m.getFinalName(),
                    m.getMavenId().getGroupId() + "-" + id + "-" + version));
            names.addAll(aliases.getOrDefault(id, List.of()));
            Path webapp = null;
            if ("war".equals(m.getPackaging())) {
                webapp = webappDir(m);
                String reason = incrementalBlock(m);
                blocked = reason != null ? reason : blocked;
            }
            modules.add(new ReactorModule(id, names, Path.of(m.getOutputDirectory()), webapp));
        }
        return new DeployItem(d.appName, archive, d.contextRoot, d.deployEjb, buildCommand(project, o, d, mp), modules, blocked);
    }

    private static Path webappDir(MavenProject m) {
        Element cfg = m.getPluginConfiguration(PLUGINS, "maven-war-plugin");
        String dir = cfg == null ? null : cfg.getChildTextTrim("warSourceDirectory");
        if (dir == null || dir.isEmpty()) {
            dir = "src/main/webapp";
        }
        Path p = Path.of(dir);
        return p.isAbsolute() ? p : Path.of(m.getDirectory()).resolve(p);
    }

    /** IDE 编译不做 war 级资源过滤，也不处理 overlay，这些工程增量同步的内容会和 Maven 构建不一致 */
    private static String incrementalBlock(MavenProject m) {
        Element cfg = m.getPluginConfiguration(PLUGINS, "maven-war-plugin");
        if (cfg == null) {
            return null;
        }
        Element overlays = cfg.getChild("overlays");
        if (overlays != null && !overlays.getChildren().isEmpty()) {
            return "模块 " + m.getMavenId().getArtifactId() + " 使用了 war overlay，改用重新部署";
        }
        Element web = cfg.getChild("webResources");
        if (web != null) {
            for (Element r : web.getChildren()) {
                if ("true".equals(r.getChildTextTrim("filtering"))) {
                    return "模块 " + m.getMavenId().getArtifactId() + " 启用了 webResources 过滤，改用重新部署";
                }
            }
        }
        return null;
    }

    /** maven-ear-plugin 中自定义的 bundleFileName，让改过文件名的模块也能匹配 */
    private static Map<String, List<String>> bundleAliases(MavenProject ear) {
        Map<String, List<String>> result = new HashMap<>();
        Element cfg = ear.getPluginConfiguration(PLUGINS, "maven-ear-plugin");
        Element modules = cfg == null ? null : cfg.getChild("modules");
        if (modules == null) {
            return result;
        }
        for (Element m : modules.getChildren()) {
            String id = m.getChildTextTrim("artifactId");
            String file = m.getChildTextTrim("bundleFileName");
            if (id != null && file != null) {
                int dot = file.lastIndexOf('.');
                result.computeIfAbsent(id, k -> new ArrayList<>()).add(dot > 0 ? file.substring(0, dot) : file);
            }
        }
        return result;
    }

    private static BuildCommand buildCommand(Project project, WasRunConfigurationOptions o, WasDeployment d, MavenProject mp)
            throws ExecutionException {
        MavenProject root = rootOf(project, mp);
        List<String> cmd = new ArrayList<>(mavenCommand(o, Path.of(root.getDirectory())));
        cmd.add("-B");
        cmd.add("-f");
        cmd.add(Path.of(root.getDirectory(), "pom.xml").toString());
        if (root != mp) {
            cmd.add("-pl");
            cmd.add(":" + mp.getMavenId().getArtifactId());
            cmd.add("-am");
        }
        cmd.addAll(ParametersListUtil.parse(d.goals));
        Map<String, String> env = new HashMap<>();
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null && sdk.getHomePath() != null) {
            env.put("JAVA_HOME", sdk.getHomePath());
        }
        return new BuildCommand(cmd, Path.of(root.getDirectory()), env);
    }

    private static MavenProject rootOf(Project project, MavenProject mp) {
        Path dir = Path.of(mp.getDirectory());
        for (MavenProject r : MavenProjectsManager.getInstance(project).getRootProjects()) {
            if (dir.startsWith(Path.of(r.getDirectory()))) {
                return r;
            }
        }
        return mp;
    }

    /** 查找顺序：运行配置指定、工程 mvnw、PATH、IDEA 自带 Maven */
    private static List<String> mavenCommand(WasRunConfigurationOptions o, Path root) throws ExecutionException {
        if (!o.getMavenExecutable().isBlank()) {
            return List.of(o.getMavenExecutable());
        }
        Path wrapper = root.resolve(SystemInfo.isWindows ? "mvnw.cmd" : "mvnw");
        if (Files.isExecutable(wrapper)) {
            return List.of(wrapper.toString());
        }
        File inPath = PathEnvironmentVariableUtil.findInPath(SystemInfo.isWindows ? "mvn.cmd" : "mvn");
        if (inPath != null) {
            return List.of(inPath.getPath());
        }
        Path bundled = Path.of(PathManager.getHomePath(), "plugins/maven/lib/maven3/bin", SystemInfo.isWindows ? "mvn.cmd" : "mvn");
        if (Files.isRegularFile(bundled)) {
            return SystemInfo.isWindows ? List.of(bundled.toString()) : List.of("/bin/sh", bundled.toString());
        }
        throw new ExecutionException("找不到 Maven，请在运行配置中指定 Maven 可执行文件");
    }
}
