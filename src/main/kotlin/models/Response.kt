package com.kxxnzstdsw.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Response(
    val id: String,
    val success: Boolean,
    val error: String? = null,
    val data: JsonElement = JsonNull
)