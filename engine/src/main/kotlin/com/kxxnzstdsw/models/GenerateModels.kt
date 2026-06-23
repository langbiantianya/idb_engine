package com.kxxnzstdsw.models

import kotlinx.serialization.Serializable

@Serializable
data class GeneratePayload(
    val tables: List<TableGenerateConfig>,
    val luaVersion: String = "luajit"
)

@Serializable
data class TableGenerateConfig(
    val script: String
)
