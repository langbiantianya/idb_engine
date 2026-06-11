package com.kxxnzstdsw.models

import kotlinx.serialization.Serializable

@Serializable
data class GeneratePayload(
    val tables: List<TableGenerateConfig>
)

@Serializable
data class TableGenerateConfig(
    val tableName: String,
    val count: Int,
    val script: String
)
