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
