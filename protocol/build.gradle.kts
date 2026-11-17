plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

// agent 运行在 WAS 自带的 Java 8 上，协议模块必须输出 Java 8 字节码
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
