plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "0.1.0"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Excel 预转换：POI 读取 .xlsx/.xls → 临时 DuckDB
    implementation(libs.poi)
    implementation(libs.poi.ooxml)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(libs.duckdb)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-duckdb")
}