plugins {
    java
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":protocol"))
    compileOnly(project(":was-stubs"))
    testCompileOnly(project(":was-stubs"))
    // 测试时用测试源码中的同名类遮蔽 Factory/Proxy 桩
    testRuntimeOnly(project(":was-stubs"))
    testImplementation("junit:junit:4.13.2")
}
