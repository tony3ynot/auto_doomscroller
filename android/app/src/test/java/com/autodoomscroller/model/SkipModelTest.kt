package com.autodoomscroller.model

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The weight file and cases that ml/tests/test_fixtures.py checks against the Python model. */
class SkipModelTest {
    private val fixture = JSONObject(File("../../ml/tests/fixtures/skip_model.json").readText())

    @Test
    fun predictionsMatchThePythonOnes() {
        val model = SkipModel.parse(fixture.getJSONObject("model").toString())
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val p = model.predictSkip(floats(case.getJSONArray("x")), floats(case.getJSONArray("frame")))
            assertEquals("case $i", case.getDouble("p_skip"), p.toDouble(), 1e-5)
        }
    }

    @Test
    fun anotherFormatIsRefused() {
        val file = fixture.getJSONObject("model").put("format", 99).toString()
        assertThrows(IllegalArgumentException::class.java) { SkipModel.parse(file) }
    }

    /** JSON null is a missing feature. */
    private fun floats(values: JSONArray) =
        FloatArray(values.length()) { if (values.isNull(it)) Float.NaN else values.getDouble(it).toFloat() }
}
