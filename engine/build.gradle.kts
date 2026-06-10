plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // JDBC Drivers — 不编译依赖，构建时复制到 drivers/
    val jdbcDrivers by configurations.creating {
        isTransitive = false
    }
    jdbcDrivers("com.mysql:mysql-connector-j:9.7.0")
    jdbcDrivers("org.postgresql:postgresql:42.7.11")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
