package com.autodoomscroller.features

import org.junit.Assert.assertEquals
import org.junit.Test

class CentreSquareTest {
    @Test
    fun portraitFrameLosesTopAndBottom() = assertEquals(Triple(0, 87, 224), centreSquare(224, 398))

    @Test
    fun wideFrameLosesTheSides() = assertEquals(Triple(37, 0, 150), centreSquare(224, 150))

    @Test
    fun squareFrameIsKeptWhole() = assertEquals(Triple(0, 0, 224), centreSquare(224, 224))
}
