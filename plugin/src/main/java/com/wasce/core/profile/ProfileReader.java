package com.wasce.core.profile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 WAS profile 配置（XMI 格式）。只按元素本地名匹配，不依赖命名空间前缀。
 */
public final class ProfileReader {
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

    private ProfileReader() {
    }

    /** 服务器标识：cell/node/server */
    public record ServerId(String cell, String node, String server) {
        @Override
        public String toString() {
            return cell + "/" + node + "/" + server;
        }

        public static ServerId parse(String s) {
            String[] p = s == null ? new String[0] : s.split("/");
            return p.length == 3 ? new ServerId(p[0], p[1], p[2]) : null;
        }
    }

    /** 列出 profile 中所有应用服务器（跳过 nodeagent、dmgr、web server） */
    public static List<ServerId> listServers(Path profileDir) throws IOException {
        List<ServerId> result = new ArrayList<>();
        Path cells = profileDir.resolve("config/cells");
        if (!Files.isDirectory(cells)) {
            throw new IOException("不是有效的 profile 目录（缺少 config/cells）: " + profileDir);
        }
        for (Path cellDir : subDirs(cells)) {
            Path nodes = cellDir.resolve("nodes");
            if (!Files.isDirectory(nodes)) {
                continue;
            }
            for (Path nodeDir : subDirs(nodes)) {
                Path index = nodeDir.resolve("serverindex.xml");
                if (!Files.isRegularFile(index)) {
                    continue;
                }
                for (Element e : children(parse(index).getDocumentElement(), "serverEntries")) {
                    if ("APPLICATION_SERVER".equals(e.getAttribute("serverType"))) {
                        result.add(new ServerId(cellDir.getFileName().toString(), nodeDir.getFileName().toString(),
                                e.getAttribute("serverName")));
                    }
                }
            }
        }
        return result;
    }

    public static ServerConfig read(Path profileDir, ServerId id) throws IOException {
        Path cellDir = profileDir.resolve("config/cells").resolve(id.cell());
        Path nodeDir = cellDir.resolve("nodes").resolve(id.node());
        Path serverDir = nodeDir.resolve("servers").resolve(id.server());
        if (!Files.isDirectory(serverDir)) {
            throw new IOException("profile 中找不到服务器 " + id + ": " + serverDir);
        }

        // 端点
        Element entry = null;
        for (Element e : children(parse(nodeDir.resolve("serverindex.xml")).getDocumentElement(), "serverEntries")) {
            if (id.server().equals(e.getAttribute("serverName"))) {
                entry = e;
            }
        }
        if (entry == null) {
            throw new IOException("serverindex.xml 中找不到服务器 " + id.server());
        }
        String[] soap = endpoint(entry, "SOAP_CONNECTOR_ADDRESS");
        if (soap == null) {
            throw new IOException("服务器 " + id + " 未配置 SOAP_CONNECTOR_ADDRESS");
        }
        String[] http = endpoint(entry, "WC_defaulthost");

        // 安全
        Path securityXml = cellDir.resolve("security.xml");
        boolean security = Files.isRegularFile(securityXml)
                && "true".equals(parse(securityXml).getDocumentElement().getAttribute("enabled"));

        // 变量：cell < node < server，后者覆盖前者
        Map<String, String> vars = new HashMap<>();
        vars.put("USER_INSTALL_ROOT", profileDir.toString());
        vars.put("LOG_ROOT", profileDir.resolve("logs").toString());
        readVariables(cellDir.resolve("variables.xml"), vars);
        readVariables(nodeDir.resolve("variables.xml"), vars);
        readVariables(serverDir.resolve("variables.xml"), vars);
        vars.putIfAbsent("SERVER_LOG_ROOT", resolve("${LOG_ROOT}/" + id.server(), vars));

        // server.xml：日志重定向与调试设置
        Element serverEl = parse(serverDir.resolve("server.xml")).getDocumentElement();
        String outName = attrOf(first(serverEl, "outputStreamRedirect"), "fileName", "${SERVER_LOG_ROOT}/SystemOut.log");
        String errName = attrOf(first(serverEl, "errorStreamRedirect"), "fileName", "${SERVER_LOG_ROOT}/SystemErr.log");
        Element jvm = null;
        for (Element pd : children(serverEl, "processDefinitions")) {
            Element j = first(pd, "jvmEntries");
            if (j != null) {
                jvm = j;
                break;
            }
        }
        boolean debugMode = jvm != null && "true".equals(jvm.getAttribute("debugMode"));
        String debugArgs = jvm == null ? "" : jvm.getAttribute("debugArgs");

        String soapHost = "*".equals(soap[0]) || soap[0].isEmpty() ? "localhost" : soap[0];
        Path serverLogRoot = Path.of(resolve(vars.get("SERVER_LOG_ROOT"), vars));
        return new ServerConfig(profileDir, id.cell(), id.node(), id.server(), soapHost, Integer.parseInt(soap[1]),
                http == null ? 0 : Integer.parseInt(http[1]), security,
                Path.of(resolve(outName, vars)), Path.of(resolve(errName, vars)),
                serverLogRoot.resolve(id.server() + ".pid"), debugMode, debugArgs, vars);
    }

    /**
     * 定位应用的已部署目录：优先读 deployment.xml 的 binariesURL，
     * 读不到时按 APP_INSTALL_ROOT/cell/app.ear 的默认位置。
     */
    public static Path binariesDir(ServerConfig c, String app) throws IOException {
        Path xml = c.profileDir.resolve("config/cells").resolve(c.cell).resolve("applications")
                .resolve(app + ".ear/deployments").resolve(app).resolve("deployment.xml");
        String url = "";
        if (Files.isRegularFile(xml)) {
            Element d = first(parse(xml).getDocumentElement(), "deployedObject");
            url = d == null ? "" : d.getAttribute("binariesURL");
        }
        if (url.isEmpty()) {
            url = "${APP_INSTALL_ROOT}/" + c.cell + "/" + app + ".ear";
        }
        Map<String, String> vars = new HashMap<>(c.variables);
        vars.putIfAbsent("APP_INSTALL_ROOT", resolve("${USER_INSTALL_ROOT}/installedApps", vars));
        return Path.of(resolve(url.replaceAll("\\$\\(([A-Za-z0-9_.]+)\\)", "\\${$1}"), vars));
    }

    /** 从 debugArgs 中解析 JDWP 端口，解析失败返回 0 */
    public static int debugPortOf(String debugArgs) {
        Matcher m = Pattern.compile("address=(?:[^,:\\s]*:)?(\\d+)").matcher(debugArgs == null ? "" : debugArgs);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String resolve(String value, Map<String, String> vars) {
        String current = value;
        // 变量可以嵌套引用，最多展开 10 层防止循环
        for (int i = 0; i < 10; i++) {
            Matcher m = VAR.matcher(current);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String v = vars.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
                changed |= v != null;
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private static void readVariables(Path file, Map<String, String> vars) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (Element e : children(parse(file).getDocumentElement(), "entries")) {
            vars.put(e.getAttribute("symbolicName"), e.getAttribute("value"));
        }
    }

    private static String[] endpoint(Element entry, String name) {
        for (Element ep : children(entry, "specialEndpoints")) {
            if (name.equals(ep.getAttribute("endPointName"))) {
                Element p = first(ep, "endPoint");
                if (p != null) {
                    return new String[]{p.getAttribute("host"), p.getAttribute("port")};
                }
            }
        }
        return null;
    }

    private static String attrOf(Element e, String attr, String def) {
        return e == null || e.getAttribute(attr).isEmpty() ? def : e.getAttribute(attr);
    }

    private static Element first(Element parent, String localName) {
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && localName.equals(localNameOf(e))) {
                result.add(e);
            }
        }
        return result;
    }

    private static String localNameOf(Element e) {
        String n = e.getLocalName() != null ? e.getLocalName() : e.getTagName();
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static List<Path> subDirs(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            ds.forEach(result::add);
        }
        result.sort(null);
        return result;
    }

    private static Document parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(file.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析失败 " + file + ": " + e.getMessage(), e);
        }
    }
}
