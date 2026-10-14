package com.mythic.localreview.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val StoreJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    // Forward compatibility: files written by a newer plugin version stay readable.
    ignoreUnknownKeys = true
    coerceInputValues = true
}
