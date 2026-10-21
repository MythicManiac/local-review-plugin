package com.mythic.localreview.format

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** SPEC 12: a conforming reader folds `examples/state` and evaluates it against `examples/content` to `examples/expected`. */
class ExampleTest {
    private fun meta(reviewId: String): ReviewMeta {
        val read = ReviewMetaCodec.decode(SpecFiles.json("examples/state/reviews/$reviewId/review.json").jsonObject)
        return (read as MetaRead.Ok).value
    }

    private fun fold(reviewId: String) = Fold.fold(meta(reviewId), Logs.readDir(SpecFiles.file("examples/state/reviews/$reviewId")))

    private fun expected(name: String): JsonObject = SpecFiles.json("examples/expected/$name.json").jsonObject

    @Test
    fun `main review folds to the expected state`() {
        val state = fold("01M4XBCYR0AQJREYKYGS5WNERF")
        assertEquals(expected("main").getValue("fold"), ReferenceShape.fold(state))
    }

    @Test
    fun `main review evaluates to the expected result`() {
        val state = fold("01M4XBCYR0AQJREYKYGS5WNERF")
        val content = ReferenceShape.Content(SpecFiles.file("examples/content"))
        val evaluation = Evaluate.evaluate(state, content.changeSet(state.header.pin), SideLines(content.blobs))
        assertEquals(expected("main").getValue("evaluated"), ReferenceShape.evaluated(evaluation))
    }

    @Test
    fun `a damaged duplicated and unknown log folds to the expected state and counts`() {
        val state = fold("20261014-1530-edge-cases")
        assertEquals(expected("edge-cases").getValue("fold"), ReferenceShape.fold(state))
        assertEquals(true, state.report.lostData)
    }

    @Test
    fun `ids named by the example exist`() {
        val ids = SpecFiles.json("examples/ids.json").jsonObject
        val state = fold("01M4XBCYR0AQJREYKYGS5WNERF")
        val named = ids.getValue("annotations").jsonObject.values.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        for (id in named) assertEquals(true, state.annotation(id) != null, "annotation $id")
    }
}
