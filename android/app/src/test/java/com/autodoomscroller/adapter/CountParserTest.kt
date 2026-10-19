package com.autodoomscroller.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountParserTest {

    @Test
    fun koreanPlainNumber() = assertEquals(27_482L, CountParser.parse("좋아요 27482개. 좋아요 수 보기"))

    @Test
    fun hiddenCountIsNull() = assertNull(CountParser.parse("좋아요 보기"))

    @Test
    fun englishWithCommas() = assertEquals(1_234L, CountParser.parse("1,234 likes"))

    @Test
    fun koreanUnit() = assertEquals(12_000L, CountParser.parse("좋아요 1.2만개"))

    @Test
    fun englishUnit() = assertEquals(12_300L, CountParser.parse("12.3K likes"))
}
