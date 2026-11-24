package com.autodoomscroller.model

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The examples and expected values that ml/tests/test_fixtures.py checks against the Python model. */
class SkipTrainerTest {
    private val fixture = JSONObject(File("../../ml/tests/fixtures/training.json").readText())
    private val names = fixture.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
    private val components = fixture.getInt("components")
    private val examples = fixture.getJSONArray("examples").let { a ->
        List(a.length()) { i ->
            val e = a.getJSONObject(i)
            TrainingExample(floats(e.getJSONArray("x")), floats(e.getJSONArray("frame")), e.getBoolean("skipped"), e.getLong("day"))
        }
    }

    @Test
    fun fittedModelPredictsWhatThePythonOneDoes() {
        val model = SkipTrainer.fit(examples, names, components, fixture.getDouble("c"))
        val probes = fixture.getJSONArray("probes")
        for (i in 0 until probes.length()) {
            val probe = probes.getJSONObject(i)
            val p = model.predictSkip(floats(probe.getJSONArray("x")), floats(probe.getJSONArray("frame")))
            assertEquals("probe $i", probe.getDouble("p_skip"), p.toDouble(), TOLERANCE)
        }
    }

    @Test
    fun rollingScoresAndAutoThresholdMatch() {
        val auto = fixture.getJSONObject("auto")
        val scores = SkipTrainer.rollingScores(examples, names, auto.getInt("days"), components)!!
        val lastDay = fixture.getJSONObject("rolling").getJSONArray("p_skip_last_day")
        val ours = scores.takeLast(lastDay.length())
        for (i in 0 until lastDay.length()) assertEquals("reel $i", lastDay.getDouble(i), ours[i].probability.toDouble(), TOLERANCE)

        val expected = auto.getJSONObject("expected")
        val got = SkipTrainer.autoThreshold(scores, auto.getDouble("precision"), auto.getInt("min_skips"))!!
        assertEquals(expected.getDouble("threshold"), got.threshold.toDouble(), 1e-6)
        assertEquals(expected.getInt("skips"), got.skips)
        assertEquals(expected.getDouble("precision"), got.precision.toDouble(), 1e-6)
        assertEquals(expected.getDouble("coverage"), got.coverage.toDouble(), 1e-6)
    }

    @Test
    fun tooFewDaysKeepAutoLocked() {
        val days = fixture.getJSONObject("rolling").getInt("test_days")
        assertNull(SkipTrainer.rollingScores(examples, names, days + 1, components))
    }

    @Test
    fun aModelSurvivesBeingWrittenAndReadBack() {
        val model = SkipTrainer.train(examples, names)!!
        val again = SkipModel.parse(model.toJson())
        val example = examples[0]
        assertEquals(model.predictSkip(example.features, example.frame), again.predictSkip(example.features, example.frame), 1e-6f)
        assertEquals(examples.size, again.trainedOn!!.reels)
        assertEquals(model.autoThreshold, again.autoThreshold)
    }

    private fun floats(values: JSONArray) =
        FloatArray(values.length()) { if (values.isNull(it)) Float.NaN else values.getDouble(it).toFloat() }

    private companion object {
        /** The offline model stops its solver a little short of the optimum; this build doesn't. */
        const val TOLERANCE = 1e-3
    }
}
