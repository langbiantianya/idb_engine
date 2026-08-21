import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
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
        // 保留 hadoop-auth（提供 PlatformName 等内部类，被 UserGroupInformation 静态引用）
        // 排除 hadoop-annotations（仅有接口注解，hadoop-common 运行时不需要）
        exclude(group = "org.apache.hadoop", module = "hadoop-annotations")
        // 排除 ZK/Curator/Netty（仅本地文件写入，不需要分布式协调）
        exclude(group = "org.apache.zookeeper", module = "zookeeper")
        exclude(group = "org.apache.curator", module = "curator-client")
        exclude(group = "org.apache.curator", module = "curator-framework")
        exclude(group = "org.apache.curator", module = "curator-recipes")
        exclude(group = "io.netty")
        // 排除 Jetty（仅本地文件写入，不需要 WebHDFS）
        exclude(group = "org.eclipse.jetty")
        exclude(group = "jakarta.servlet", module = "jakarta.servlet-api")
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j", module = "reload4j")
        exclude(group = "com.google.re2j", module = "re2j")
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.jcraft", module = "jsch")
        exclude(group = "commons-cli", module = "commons-cli")
        // 保留 commons-configuration2、commons-lang3、commons-text、commons-collections4 等 Hadoop 实际运行依赖
        exclude(group = "org.apache.kerby", module = "kerb-core")
        exclude(group = "org.apache.kerby", module = "kerb-asn1")
        exclude(group = "org.apache.kerby", module = "kerby-pkix")
        exclude(group = "org.apache.kerby", module = "kerby-util")
        exclude(group = "io.dropwizard.metrics", module = "metrics-core")
        exclude(group = "dnsjava", module = "dnsjava")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }
    implementation(libs.parquet.hadoop) {
        // 排除 parquet-hadoop 中不需要的 transitive dependencies
        exclude(group = "org.apache.htrace", module = "htrace-core4")
        exclude(group = "org.fusesource.leveldbjni", module = "leveldbjni-all")
        exclude(group = "com.twitter", module = "parquet-hadoop-bundle")
        // 排除不需要的压缩 codec
        exclude(group = "org.xerial.snappy", module = "snappy-java")
        exclude(group = "io.airlift", module = "aircompressor")
    }

    // gRPC 服务端（Netty 实现，HTTP/2 over TCP loopback）
    // - grpc-stub: io.grpc.Server / ServerBuilder（transport.serverBuilder() 调用）
    // - grpc-protobuf: io.grpc.protobuf.ProtoUtils（生成代码 IdbEngineGrpc.getServiceDescriptor）
    // - grpc-netty-shaded: NettyServerBuilder / NettyChannelBuilder + epoll（IPC transport）
    // - grpc-kotlin-stub: io.grpc.kotlin.*（生成代码 IdbEngineGrpcKt 的协程 stub / IdbEngineCoroutineImplBase）
    // - protobuf-kotlin-lite: com.google.protobuf.kotlin.*（生成代码 *Kt DSL 与 .Dsl 内部类）
    //   grpc-core / grpc-api / grpc-context / grpc-util 由 grpc-stub 传递依赖
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)

    // JDBC Drivers — 不编译依赖，构建时复制到 drivers/
    val jdbcDrivers by configurations.creating {
        isTransitive = false
    }
    jdbcDrivers(libs.mysql.connector)
    jdbcDrivers(libs.postgresql)
    jdbcDrivers(libs.h2)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(libs.h2)
    // 集成测试需要引用方言 SPI 接口
    testImplementation(project(":dialect-h2"))
    testImplementation(project(":dialect-mysql"))
    testImplementation(project(":dialect-postgresql"))
}

// Protobuf — 启用 java + kotlin 双代码生成
// java (protoc-gen-grpc-java) 生成 Java-style IdbEngineGrpc.java + gRPC service descriptor，
//   这是 grpc-kotlin 生成 stub 内部依赖的（IdbEngineGrpcKt.kt 引用 IdbEngineGrpc.getServiceDescriptor）
// kotlin (grpckt) 生成 IdbEngineGrpcKt 协程 stub + *Kt DSL builder，业务层用 suspend handle() + Kotlin DSL
//
// 版本对齐（关键约束 — 整组必须配套）：
// - protoc 3.25.5           ← 主二进制，生成 Java message class（Request.java 等）
// - protoc-gen-grpc-java 1.68.0  ← 生成 Java gRPC stub（IdbEngineGrpc.java）
// - protobuf-java 3.25.8    ← 运行/编译时 protobuf 库（grpc-protobuf 1.76 传递依赖）
// - grpc-protobuf 1.76.0    ← io.grpc.protobuf.ProtoUtils 运行时
// - grpc-kotlin-stub 1.4.1  ← io.grpc.kotlin.* 运行时
// 不能用 4.x 版的 protoc 或 protoc-gen-grpc-java — 它们生成的代码依赖 protobuf-java 4.26+ 引入的
// RuntimeVersion / GeneratedMessage.isStringEmpty，与 grpc-protobuf 1.76 传递的 protobuf-java 3.25.8 不兼容。
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            // 生成 Kotlin DSL（com.kxxnzstdsw.grpc.ColumnDefKt 等），业务层通过 columnDef { ... } 构造
            task.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("src/main/proto")
        }
    }
}

// proto 文件仅供 protoc 消费，不打包到 JAR
tasks.processResources {
    exclude("**/*.proto")
}

// 把 proto 生成目录加入 Kotlin 编译输入
kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/source/proto/main/java"))
kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/source/proto/main/grpc"))
kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/source/proto/main/grpckt"))
kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/source/proto/main/kotlin"))

tasks.test {
    useJUnitPlatform()
}

// 瘦 JAR：只打包 engine 代码和资源，不含依赖
tasks.jar {
    archiveBaseName.set("idb-engine")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.kxxnzstdsw.server.IdbEngineServer"
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") {
            "libs/${it.name}"
        }
    }
}

// 复制运行时依赖到 build/libs/libs/（不含 JDBC 驱动和方言插件）
val jdbcDriverNames = configurations.named("jdbcDrivers").map { deps ->
    deps.files.map { it.name }.toSet()
}
val dialectProjectNames = setOf("idb-dialect-mysql", "idb-dialect-postgresql", "idb-dialect-h2")
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
    from(project(":dialect-h2").tasks.named("jar"))
    into(layout.buildDirectory.dir("libs/dialects"))
}

// 打包完成后自动复制依赖、驱动和方言
tasks.jar {
    finalizedBy(copyDeps, copyDrivers, copyDialects, copyWinutils)
}

// 下载 winutils.exe 到 build/libs/bin/winutils.exe（仅 Windows 需要，Parquet 写本地文件依赖）
// Hadoop 3.3.5 的 winutils 与 3.5.0 二进制兼容
val copyWinutils by tasks.registering {
    description = "下载windows上的hadoop winutils 依赖"
    val winutilsUrl = "https://raw.githubusercontent.com/cdarlint/winutils/refs/heads/master/hadoop-3.3.6/bin/winutils.exe"
    val outDir = layout.buildDirectory.dir("libs/bin")
    outputs.dir(outDir)
    doLast {
        val target = outDir.get().file("winutils.exe").asFile
        if (target.exists() && target.length() > 0) {
            return@doLast
        }
        outDir.get().asFile.mkdirs()
        logger.lifecycle("Downloading winutils.exe to ${target.absolutePath}")
        // 通过 ant.get 走 Gradle 内置 HttpClient（与 Gradle 自身下载依赖相同的网络栈，最稳）
        ant.invokeMethod("get", mapOf(
            "src" to winutilsUrl,
            "dest" to target.absolutePath,
            "verbose" to true,
            "retries" to 3
        ))
        logger.lifecycle("winutils.exe downloaded (${target.length()} bytes)")
    }
}