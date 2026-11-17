# WebSphere CE Connector

IntelliJ IDEA Community 的 IBM WebSphere Application Server 8.5 连接插件，不依赖 Ultimate 的 Java EE 支持。

## 功能

- 本地启停 WAS 服务器，日志输出到 Console；Debug 时自动 attach
- 连接已在运行的服务器，或连接远程服务器
- Maven 构建并部署 EAR / WAR（独立 WAR 自动包装为 EAR，支持自定义 context root）、EJB
- 增量更新：静态资源只同步；类文件在 Debug 时 HotSwap，否则重启应用；描述符改动重新部署
- 远程部署：上传归档、部分更新、SSL 证书指纹确认后导入

兼容 IntelliJ IDEA Community 2023.1（231）及以上，已用 Plugin Verifier 校验 2023.1.3 与 2025.2。

> 状态：尚未在真实 WAS 环境上验证，目前通过模拟 WAS 环境与自动化测试验证。

## 模块

| 模块 | 说明 |
|---|---|
| `protocol` | IDE 与 agent 之间的通信协议（Java 8） |
| `agent` | 在 WAS 自带 JRE 中运行的管理进程，通过 JMX SOAP 连接器访问服务器（Java 8） |
| `was-stubs` | IBM 管理 API 的签名桩，仅用于编译，不随插件分发 |
| `fake-was` | 模拟 WAS 安装，用于开发和测试 |
| `plugin` | IDEA 插件 |
| `devtools/demo-project` | Maven EAR + WAR 演示工程，供 `runIde` 使用 |

## 构建

需要 JDK 17 运行 Gradle；模拟 WAS 需要本机 JDK 8（可用 `-PfakeWasJavaHome=...` 指定）。

```bash
./gradlew test                    # 全部测试
./gradlew :plugin:verifyPlugin    # 兼容性校验
./gradlew :plugin:buildPlugin     # 产物：plugin/build/distributions/wasce-<版本>.zip
./gradlew :plugin:runIde          # 启动沙箱 IDE，自动登记模拟 WAS 并打开演示工程
```

## 使用

1. Settings | Tools | WebSphere Servers：登记 WAS 安装目录（WAS_HOME）
2. 新建运行配置 WebSphere Server (CE)，选择本地或远程，填写 profile / 服务器 / 部署项
3. Run 或 Debug；改代码后用 Run | Update WebSphere Application 同步到服务器
