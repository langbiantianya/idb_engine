plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.gradleup.shadow") version "9.3.0+"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // HikariCP Connection Pool
    implementation("com.zaxxer:HikariCP:7.0.2")

    // Database Drivers
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("org.postgresql:postgresql:42.7.11")

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
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.kxxnzstdsw.MainKt"
    }
}