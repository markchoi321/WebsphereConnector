import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    // 2.14.0 起最低平台版本为 233，为支持 2023.1（231）固定在 2.13.1
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 随插件分发、在 WAS JRE 中运行的 agent 及其依赖
val agentJars: Configuration = configurations.create("agentJars") {
    isCanBeConsumed = false
    isTransitive = true
}

dependencies {
    implementation(project(":protocol"))
    agentJars(project(":agent"))

    intellijPlatform {
        intellijIdeaCommunity("2023.1.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

intellijPlatform {
    projectName = "wasce"
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.1.3")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
        }
    }
}

tasks {
    prepareSandbox {
        from(agentJars) {
            into("wasce/lib/agent")
        }
    }

    prepareTestSandbox {
        from(agentJars) {
            into("wasce/lib/agent")
        }
    }

    test {
        dependsOn(":fake-was:prepareFakeWas")
        inputs.files(agentJars)
        val agentPath = agentJars.asPath
        val fakeWas = project(":fake-was").layout.buildDirectory.dir("fake-was").get().asFile.absolutePath
        systemProperty("wasce.agent.classpath", agentPath)
        systemProperty("wasce.fakeWas.home", fakeWas)
        systemProperty("wasce.demo.project", rootProject.layout.projectDirectory.dir("devtools/demo-project").asFile.absolutePath)
        // 缩短模拟 stopServer 的等待时间，加快"忽略停止"场景的测试
        environment("FAKEWAS_STOP_WAIT_MS", "3000")
    }
}

// 开发环境：为 runIde 沙箱登记模拟 WAS，并生成真实的 Maven 多模块演示工程（EAR + WAR）
val demoProjectDir = layout.buildDirectory.dir("demo-project")

val copyDemoProject by tasks.registering(Copy::class) {
    val fakeWas = project(":fake-was").layout.buildDirectory.dir("fake-was").get().asFile.absolutePath
    from(rootProject.layout.projectDirectory.dir("devtools/demo-project"))
    into(demoProjectDir)
    filteringCharset = "UTF-8"
    filter { line: String -> line.replace("@FAKE_WAS@", fakeWas) }
}

val prepareDevEnvironment by tasks.registering {
    group = "wasce"
    description = "为 runIde 登记模拟 WAS 并生成演示工程"
    dependsOn(":fake-was:prepareFakeWas", tasks.prepareSandbox, copyDemoProject)
    val fakeWas = project(":fake-was").layout.buildDirectory.dir("fake-was").get().asFile.absolutePath
    val optionsDir = tasks.prepareSandbox.flatMap { it.sandboxConfigDirectory }.map { it.dir("options").asFile }
    doLast {
        val installations = File(optionsDir.get(), "wasce-installations.xml")
        // 已有登记时不覆盖，保留在沙箱中手动添加的安装
        if (!installations.exists()) {
            installations.parentFile.mkdirs()
            installations.writeText(
                """
                <application>
                  <component name="WasCeInstallations">
                    <option name="installations">
                      <list>
                        <Entry>
                          <option name="name" value="WAS 8.5.5.99（模拟）" />
                          <option name="home" value="$fakeWas" />
                        </Entry>
                      </list>
                    </option>
                  </component>
                </application>
                """.trimIndent()
            )
        }
    }
}

tasks.runIde {
    dependsOn(prepareDevEnvironment)
    val demo = demoProjectDir.map { it.asFile.absolutePath }
    argumentProviders.add(CommandLineArgumentProvider { listOf(demo.get()) })
}
