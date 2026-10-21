package com.mythic.localreview.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** Access to the spec submodule for tests outside the `format` package. */
object SpecFilesForStore {
    fun file(path: String): Path = SpecFiles.file(path).toPath()
    fun parse(file: Path): JsonObject = Json.parseToJsonElement(java.nio.file.Files.readString(file)) as JsonObject
}
