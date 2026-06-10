plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "0.1.0"

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-api")
}
