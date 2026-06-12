plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "0.1.1"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-postgresql")
}
