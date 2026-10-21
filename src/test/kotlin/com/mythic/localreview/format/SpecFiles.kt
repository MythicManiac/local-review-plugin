package com.mythic.localreview.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The frozen spec, checked out as the `spec` submodule at the baseline this plugin implements. */
object SpecFiles {
    /** The baseline tag the plugin targets. Changing it is a deliberate act: move the submodule and re-run every test. */
    const val BASELINE = "baseline-1"

    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "spec") }
        .first { File(it, "SPEC_VERSION").isFile }

    fun file(path: String) = File(root, path)
    fun json(path: String): JsonElement = Json.parseToJsonElement(file(path).readText(Charsets.UTF_8))
    fun vectors(name: String): JsonObject = json("vectors/$name.json").jsonObject
}

fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }
fun JsonElement.interval(): Interval = jsonArray.let { Interval(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
fun JsonElement?.intervalOrNull(): Interval? = if (this == null || this is JsonNull) null else interval()
fun JsonElement.intervals(): List<Interval> = jsonArray.map { it.interval() }
fun JsonElement.isNullValue() = this is JsonNull
fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.content == "true"
