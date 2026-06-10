plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

tasks.jar {
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveBaseName.set("idb-api")
}
