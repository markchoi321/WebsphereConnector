plugins {
    java
}

// IBM API 签名桩：只用于编译 agent，运行时使用 WAS 自带的真实 jar，不打包分发
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}
