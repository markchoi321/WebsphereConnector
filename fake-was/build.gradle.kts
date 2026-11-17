plugins {
    java
}

// 模拟服务器运行在 JDK 8 上，与真实 WAS 8.5 的 Java 版本保持一致
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

// 模拟"WAS 自带 JRE"所用的 JDK 8，可用 -PfakeWasJavaHome=... 覆盖
val java8Home: Provider<String> = providers.gradleProperty("fakeWasJavaHome").orElse(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
        .map { it.metadata.installationPath.asFile.absolutePath }
)

val fakeWasDir = layout.buildDirectory.dir("fake-was")

val prepareFakeWas by tasks.registering(Sync::class) {
    group = "wasce"
    description = "生成模拟 WAS 安装目录（开发与测试专用）"
    val home = fakeWasDir.get().asFile.absolutePath
    val javaHome = java8Home
    into(fakeWasDir)
    from("src/dist") {
        filteringCharset = "UTF-8"
        filter { line: String -> line.replace("@WAS_HOME@", home).replace("@JAVA8_HOME@", javaHome.get()) }
    }
    from(tasks.jar) {
        into("lib")
        rename { "fake-was.jar" }
    }
    // 重新生成时保留运行产生的日志
    preserve {
        include("profiles/*/logs/**")
    }
    doLast {
        File(home).walk()
            .filter { it.isFile && (it.name.endsWith(".sh") || it.name == "java") }
            .forEach { it.setExecutable(true) }
    }
}
