package com.autodoomscroller.handsfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureInterpreterTest {

    /** Feeds frames 100 ms apart (the camera runs at about 10 fps) and collects the commands. */
    private fun run(vararg frames: Pair<Float?, Float?>, thumb: List<Float?> = emptyList()): List<HandsFreeCommand> {
        val interpreter = GestureInterpreter()
        val count = maxOf(frames.size, thumb.size)
        return (0 until count).mapNotNull { i ->
            val (left, right) = frames.getOrElse(i) { OPEN }
            interpreter.onFrame(GestureFrame(atMs = i * 100L, blinkLeft = left, blinkRight = right, thumbUp = thumb.getOrNull(i)))
        }
    }

    @Test
    fun doubleWinkIsNextDecidedAtTheFirstWink() {
        val commands = run(OPEN, WINK, WINK, OPEN, OPEN, WINK, WINK, OPEN)
        assertEquals(listOf(HandsFreeCommand.Next(decidedAtMs = 100, by = NextGesture.DOUBLE_WINK)), commands)
    }

    @Test
    fun eitherEyeCounts() = assertEquals(1, run(OPEN, WINK, OPEN, OTHER_WINK, OPEN).size)

    @Test
    fun aWinkThatSquintsTheOtherEyeCounts() = assertEquals(1, run(OPEN, SQUINT_WINK, OPEN, SQUINT_WINK, OPEN).size)

    @Test
    fun naturalBlinksAreIgnored() = assertTrue(run(OPEN, BLINK, OPEN, BLINK, OPEN, BLINK, OPEN).isEmpty())

    @Test
    fun aRestingDifferenceBetweenTheEyesIsNotAWink() {
        // One lid rests lower; a blink that starts from there is uneven but not a wink.
        val resting = 0.3f to 0f
        val uneven = 0.55f to 0.2f
        val warmUp = Array(30) { resting }
        assertTrue(run(*warmUp, uneven, resting, uneven, resting).isEmpty())
    }

    @Test
    fun aSingleWinkDoesNothing() = assertTrue(run(OPEN, WINK, OPEN, OPEN, OPEN).isEmpty())

    @Test
    fun winksTooFarApartDoNothing() {
        val gap = Array(15) { OPEN }
        assertTrue(run(OPEN, WINK, OPEN, *gap, WINK, OPEN).isEmpty())
    }

    @Test
    fun aLongClosedEyeIsNotAWink() {
        val long = Array(10) { WINK }
        assertTrue(run(OPEN, *long, OPEN, WINK, OPEN).isEmpty())
    }

    @Test
    fun aLongBlinkIsNextDecidedWhenTheEyesShut() {
        val commands = run(OPEN, *Array(9) { BLINK }, OPEN)
        assertEquals(listOf(HandsFreeCommand.Next(decidedAtMs = 100, by = NextGesture.LONG_BLINK)), commands)
    }

    @Test
    fun aLongBlinkMayEaseOpenBeforeItEnds() {
        val easing = 0.45f to 0.42f
        assertEquals(1, run(OPEN, *Array(5) { BLINK }, *Array(4) { easing }, OPEN).size)
    }

    @Test
    fun restingTheEyesIsNotACommand() = assertTrue(run(OPEN, *Array(30) { BLINK }, OPEN).isEmpty())

    @Test
    fun losingTheFaceDuringALongBlinkDoesNothing() =
        assertTrue(run(OPEN, *Array(9) { BLINK }, NO_FACE, OPEN).isEmpty())

    @Test
    fun noFaceEndsAWinkWithoutCounting() = assertTrue(run(OPEN, NO_FACE, NO_FACE, OPEN).isEmpty())

    @Test
    fun heldThumbsUpLikesOnce() {
        val held = List(20) { 0.9f }
        assertEquals(listOf(HandsFreeCommand.Like(decidedAtMs = 0)), run(thumb = held))
    }

    @Test
    fun briefThumbsUpDoesNothing() = assertTrue(run(thumb = listOf(0.9f, 0.9f, 0.9f, null, null, null)).isEmpty())

    @Test
    fun aDroppedFrameDoesNotRestartTheHold() {
        val commands = run(thumb = listOf(0.9f, 0.9f, 0.9f, null, 0.9f, 0.9f, 0.9f))
        assertEquals(listOf(HandsFreeCommand.Like(decidedAtMs = 0)), commands)
    }

    @Test
    fun raisingTheThumbAgainLikesAgain() {
        val twice = List(6) { 0.9f } + List(5) { null } + List(6) { 0.9f }
        assertEquals(2, run(thumb = twice).size)
    }

    @Test
    fun cooldownStopsAQuickSecondCommand() {
        // A like right after a double wink is dropped.
        val commands = run(OPEN, WINK, OPEN, WINK, OPEN, OPEN, OPEN, OPEN, thumb = listOf(null, null, null, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f))
        assertEquals(listOf(HandsFreeCommand.Next(decidedAtMs = 100, by = NextGesture.DOUBLE_WINK)), commands)
    }

    private companion object {
        val OPEN = 0.05f to 0.05f
        val WINK = 0.8f to 0.1f
        val OTHER_WINK = 0.1f to 0.8f
        val SQUINT_WINK = 0.75f to 0.4f
        val BLINK = 0.8f to 0.8f
        val NO_FACE: Pair<Float?, Float?> = null to null
    }
}
