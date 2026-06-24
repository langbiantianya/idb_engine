plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.luajava)

    // Lua 引擎（编译依赖 + 运行时原生库）
    implementation(libs.luajit)
    implementation(libs.lua51)
    implementation(libs.lua52)
    implementation(libs.lua53)
    implementation(libs.lua54)
    implementation(libs.lua55)
    val luaVersion = libs.versions.luajava.get()
    runtimeOnly("party.iroiro.luajava:luajit-platform:$luaVersion:natives-desktop")
    runtimeOnly("party.iroiro.luajava:lua51-platform:$luaVersion:natives-desktop")
    runtimeOnly("party.iroiro.luajava:lua52-platform:$luaVersion:natives-desktop")
    runtimeOnly("party.iroiro.luajava:lua53-platform:$luaVersion:natives-desktop")
    runtimeOnly("party.iroiro.luajava:lua54-platform:$luaVersion:natives-desktop")
    runtimeOnly("party.iroiro.luajava:lua55-platform:$luaVersion:natives-desktop")

    // Excel 导出（POI SXSSF 流式 API）
    implementation(libs.poi)
    implementation(libs.poi.ooxml)

    // Parquet 列式存储 — 只保留 hadoop-common 中的 Configuration/Path 核心 API
    // parquet-hadoop 本身已包含 Parquet 核心库，这里仅补文件系统抽象层
    implementation(libs.hadoop.common) {
        // 排除 HDFS / MapReduce / YARN 等分布式模块
        exclude(group = "org.apache.hadoop", module = "hadoop-auth")
        exclude(group = "org.apache.hadoop", module = "hadoop-hdfs")
        exclude(group = "org.apache.hadoop", module = "hadoop-hdfs-client")
        exclude(group = "org.apache.hadoop", module = "hadoop-hdfs-native-client")
        exclude(group = "org.apache.hadoop", module = "hadoop-mapreduce-client-core")
        exclude(group = "org.apache.hadoop", module = "hadoop-mapreduce-client-common")
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-api")
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-common")
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-client")
        // 排除云存储
        exclude(group = "org.apache.hadoop", module = "hadoop-azure")
        exclude(group = "org.apache.hadoop", module = "hadoop-amazon")
        exclude(group = "org.apache.hadoop", module = "hadoop-google")
        // 排除 WebHDFS / HTTP 相关（仅本地写入）
        exclude(group = "jakarta.servlet.jsp", module = "jakarta.servlet.jsp-api")
        exclude(group = "jakarta.ws.rs", module = "jakarta.ws.rs-api")
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
        exclude(group = "org.apache.httpcomponents", module = "httpcore")
        exclude(group = "commons-net", module = "commons-net")
        // 排除 WebHDFS / Jersey 相关（仅本地文件写入）
        exclude(group = "org.glassfish.jersey.core", module = "jersey-server")
        exclude(group = "org.glassfish.jersey.core", module = "jersey-common")
        exclude(group = "org.glassfish.jersey.core", module = "jersey-client")
        exclude(group = "org.glassfish.jersey.containers", module = "jersey-container-servlet")
        exclude(group = "org.glassfish.jersey.containers", module = "jersey-container-servlet-core")
        exclude(group = "org.glassfish.jersey.inject", module = "jersey-hk2")
        exclude(group = "org.glassfish.hk2.external", module = "jakarta.inject")
        exclude(group = "jakarta.annotation", module = "jakarta.annotation-api")
        exclude(group = "jakarta.validation", module = "jakarta.validation-api")
        exclude(group = "org.glassfish.hk2", module = "osgi-resource-locator")
        // 排除 hadoop-common 中不需要的 transitive dependencies
        exclude(group = "org.apache.htrace", module = "htrace-core4")
        exclude(group = "com.fasterxml.woodstox", module = "woodstox-core")
        exclude(group = "org.codehaus.woodstox", module = "stax2-api")
        exclude(group = "org.fusesource.leveldbjni", module = "leveldbjni-all")
        exclude(group = "org.apache.hadoop", module = "hadoop-annotations")
        // 排除 hadoop-common 的 transitive dependencies（仅本地文件写入）
        // 排除 hadoop-common 的 transitive dependencies（仅本地文件写入）
        exclude(group = "org.apache.hadoop.thirdparty", module = "hadoop-shaded-protobuf_3_25")
        exclude(group = "org.apache.hadoop.thirdparty", module = "hadoop-shaded-guava")
        exclude(group = "org.apache.yetus", module = "audience-annotations")
        // 排除 ZK/Curator/Netty（仅本地文件写入，不需要分布式协调）
        exclude(group = "org.apache.zookeeper", module = "zookeeper")
        exclude(group = "org.apache.curator", module = "curator-client")
        exclude(group = "org.apache.curator", module = "curator-framework")
        exclude(group = "org.apache.curator", module = "curator-recipes")
        exclude(group = "io.netty")
        // 排除 Jetty（仅本地文件写入，不需要 WebHDFS）
        exclude(group = "org.eclipse.jetty")
        // 排除 Avro（仅本地文件写入，不需要序列化框架）
        exclude(group = "org.apache.avro", module = "avro")
        // 排除 Jetty 的 transitive
        exclude(group = "jakarta.servlet", module = "jakarta.servlet-api")
        exclude(group = "org.slf4j", module = "slf4j-reload4j") // reload4j 是 jetty/zookeeper 的日志桥接
        exclude(group = "ch.qos.reload4j", module = "reload4j")
        exclude(group = "com.google.re2j", module = "re2j")
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.jcraft", module = "jsch") // SSH/SFTP，用于 HDFS 高可用
        exclude(group = "commons-cli", module = "commons-cli") // 仅命令行工具需要，代码中未使用
        exclude(group = "org.apache.commons", module = "commons-configuration2") // 仅 HDFS 配置读取需要，代码中未使用
        // 排除 hadoop-common 的直接依赖（仅本地文件写入，不需要分布式功能）
        exclude(group = "org.apache.commons", module = "commons-math3") // Hadoop 内部数值计算
        exclude(group = "org.apache.kerby", module = "kerb-core")
        exclude(group = "org.apache.kerby", module = "kerby-asn1")
        exclude(group = "org.apache.kerby", module = "kerby-pkix")
        exclude(group = "org.apache.kerby", module = "kerby-util")
        exclude(group = "org.locationtech.jts", module = "jts-core") // 空间几何库，用于 GeoTools
        exclude(group = "io.dropwizard.metrics", module = "metrics-core") // JVM 监控指标
        exclude(group = "dnsjava", module = "dnsjava") // DNS 解析，用于 HDFS 高可用
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on") // Bouncy Castle 加密
    }
    implementation(libs.parquet.hadoop) {
        // 排除 parquet-hadoop 中不需要的 transitive dependencies
        exclude(group = "org.apache.htrace", module = "htrace-core4")
        exclude(group = "com.fasterxml.woodstox", module = "woodstox-core")
        exclude(group = "org.codehaus.woodstox", module = "stax2-api")
        exclude(group = "org.fusesource.leveldbjni", module = "leveldbjni-all")
        exclude(group = "com.twitter", module = "parquet-hadoop-bundle") // 内部 bundle，统一使用 parquet-*-bundle
        // 排除不需要的压缩 codec
        exclude(group = "org.xerial.snappy", module = "snappy-java") // Snappy 压缩，本地写入不需要
        exclude(group = "io.airlift", module = "aircompressor") // LZ4 压缩，本地写入不需要
    }
    implementation(libs.parquet.hadoop) {
        // 排除 parquet-hadoop 中不需要的 transitive dependencies
        exclude(group = "org.apache.htrace", module = "htrace-core4")
        exclude(group = "com.fasterxml.woodstox", module = "woodstox-core")
        exclude(group = "org.codehaus.woodstox", module = "stax2-api")
        exclude(group = "org.fusesource.leveldbjni", module = "leveldbjni-all")
        exclude(group = "com.twitter", module = "parquet-hadoop-bundle") // 内部 bundle，统一使用 parquet-*-bundle
    }

    // JDBC Drivers — 不编译依赖，构建时复制到 drivers/
    val jdbcDrivers by configurations.creating {
        isTransitive = false
    }
    jdbcDrivers(libs.mysql.connector)
    jdbcDrivers(libs.postgresql)

    testImplementation(kotlin("test"))
}

// 瘦 JAR：只打包 engine 代码和资源，不含依赖
tasks.jar {
    archiveBaseName.set("idb-engine")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.kxxnzstdsw.MainKt"
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") {
            "libs/${it.name}"
        }
    }
}

// 复制运行时依赖到 build/libs/libs/（不含 JDBC 驱动和方言插件）
val jdbcDriverNames = configurations.named("jdbcDrivers").map { deps ->
    deps.files.map { it.name }.toSet()
}
val dialectProjectNames = setOf("idb-dialect-mysql", "idb-dialect-postgresql")
val copyDeps by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath) {
        exclude { element ->
            jdbcDriverNames.get().contains(element.file.name) ||
            dialectProjectNames.any { element.file.name.startsWith(it) }
        }
    }
    into(layout.buildDirectory.dir("libs/libs"))
}

// 复制 JDBC 驱动到 build/libs/drivers/
val copyDrivers by tasks.registering(Copy::class) {
    from(configurations.named("jdbcDrivers"))
    into(layout.buildDirectory.dir("libs/drivers"))
}

// 复制方言插件到 build/libs/dialects/
val copyDialects by tasks.registering(Copy::class) {
    from(project(":dialect-mysql").tasks.named("jar"))
    from(project(":dialect-postgresql").tasks.named("jar"))
    into(layout.buildDirectory.dir("libs/dialects"))
}

// 打包完成后自动复制依赖、驱动和方言
tasks.jar {
    finalizedBy(copyDeps, copyDrivers, copyDialects)
}
