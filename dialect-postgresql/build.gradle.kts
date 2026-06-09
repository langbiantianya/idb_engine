plugins {
    kotlin("jvm") version "2.3.21"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveBaseName.set("idb-dialect-postgresql")
}
