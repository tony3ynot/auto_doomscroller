package com.autodoomscroller.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeParsingTest {

    @Test
    fun channel() = assertEquals("duktem", channelFromDescription("@duktem 채널로 이동"))

    @Test
    fun koreanChannelHandle() = assertEquals("먹기전에-l8b", channelFromDescription("@먹기전에-l8b 채널로 이동"))

    @Test
    fun otherDescriptionIsNotAChannel() = assertNull(channelFromDescription("@duktem을(를) 구독합니다."))

    @Test
    fun timeBar() = assertEquals(8_000L to 58_000L, parseTimeBar("0분 58초 중 0분 8초"))

    @Test
    fun timeBarWithHours() = assertEquals(65_000L to 3_723_000L, parseTimeBar("1시간 2분 3초 중 1분 5초"))

    @Test
    fun unrelatedTextIsNotATimeBar() = assertNull(parseTimeBar("동영상 진행률"))

    @Test
    fun adSlotLabels() {
        assertTrue(isAdLabel("광고"))
        assertTrue(isAdLabel("스폰서"))
        assertTrue(isAdLabel("아쿠아랜드: 크레이지 서바이벌\n광고\n별점 4.6\n무료"))
    }

    @Test
    fun sponsoredContentIsNotAnAdSlot() {
        assertFalse(isAdLabel("유료 광고 포함"))
        assertFalse(isAdLabel("수수료 지급"))
        assertFalse(isAdLabel("광고 없이 보는 법"))
        assertTrue(isPaidPromotionLabel("수수료 지급"))
        assertTrue(isPaidPromotionLabel("유료 광고 포함"))
    }

    @Test
    fun counts() {
        assertEquals(31_000L, CountParser.parse("다른 사용자 3.1만명과 함께 이 동영상에 좋아요 표시"))
        assertEquals(770L, CountParser.parse("다른 사용자 770명과 함께 이 동영상에 좋아요 표시"))
        assertEquals(534L, CountParser.parse("댓글 534개 보기"))
        assertNull(CountParser.parse("댓글을 사용할 수 없습니다."))
    }
}
