package com.kxxnzstdsw.handlers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.management.ManagementFactory

object SystemHandler {

    fun info(): JsonElement {
        val runtime = Runtime.getRuntime()
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val runtimeBean = ManagementFactory.getRuntimeMXBean()

        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        return buildJsonObject {
            put("jvmVersion", System.getProperty("java.version"))
            put("jvmVendor", System.getProperty("java.vendor"))
            put("jvmName", System.getProperty("java.vm.name"))
            put("osName", osBean.name)
            put("osArch", osBean.arch)
            put("osVersion", osBean.version)
            put("availableProcessors", osBean.availableProcessors)
            putJsonObject("memory") {
                put("max", maxMemory)
                put("total", totalMemory)
                put("used", usedMemory)
                put("free", freeMemory)
            }
            put("uptime", runtimeBean.uptime)
            put("pid", runtimeBean.pid)
        }
    }
}
