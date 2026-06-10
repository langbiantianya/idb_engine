plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.jar {
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveBaseName.set("idb-dialect-mysql")
}
