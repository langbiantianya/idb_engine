plugins {
    kotlin("jvm") version "2.3.21"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveBaseName.set("idb-api")
}
