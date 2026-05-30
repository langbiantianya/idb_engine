plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.gradleup.shadow") version "9.3.0+"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"


dependencies {
    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
// Source: https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // HikariCP Connection Pool
    implementation("com.zaxxer:HikariCP:7.0.2")

    // Database Drivers — 不编译依赖，构建时复制到 drivers/ 由 DriverLoader 动态加载
    val jdbcDrivers by configurations.creating {
        isTransitive = false
    }
    jdbcDrivers("com.mysql:mysql-connector-j:9.7.0")
    jdbcDrivers("org.postgresql:postgresql:42.7.11")

    // Logging (redirect to stderr)
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.5.13")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("idb-engine")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.kxxnzstdsw.MainKt"

    }
}

// 瘦 JAR：只打包项目代码和资源，不含依赖
tasks.jar {
    archiveBaseName.set("idb-engine")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.kxxnzstdsw.MainKt"
        // classpath 指向同级 libs/ 目录
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") {
            "libs/${it.name}"
        }
    }
}

// 复制所有运行时依赖到 build/libs/libs/（不含 JDBC 驱动）
val jdbcDriverNames = configurations.named("jdbcDrivers").map { deps ->
    deps.files.map { it.name }.toSet()
}
val copyDeps by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath) {
        exclude { element -> jdbcDriverNames.get().contains(element.file.name) }
    }
    into(layout.buildDirectory.dir("libs/libs"))
}

// 复制 JDBC 驱动到 build/libs/drivers/
val copyDrivers by tasks.registering(Copy::class) {
    from(configurations.named("jdbcDrivers"))
    into(layout.buildDirectory.dir("libs/drivers"))
}

// 打包完成后自动复制依赖和驱动
tasks.jar {
    finalizedBy(copyDeps, copyDrivers)
}