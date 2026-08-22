plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "0.1.0"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(libs.sqlite)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-sqlite")
}