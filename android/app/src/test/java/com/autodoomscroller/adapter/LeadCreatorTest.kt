package com.autodoomscroller.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeadCreatorTest {

    @Test
    fun koreanCollab() = assertEquals("wo.0z", leadCreator("wo.0z 외 2명 "))

    @Test
    fun koreanPair() = assertEquals("plforindia", leadCreator("plforindia님과 berbo9님"))

    @Test
    fun englishCollab() = assertEquals("wo.0z", leadCreator("wo.0z and 1 other"))

    @Test
    fun englishPair() = assertEquals("plforindia", leadCreator("plforindia and berbo9"))

    @Test
    fun trailingSpacesOnly() = assertEquals("seongnam_bundang", leadCreator("seongnam_bundang  "))

    @Test
    fun nameThatLooksLikeASuffixIsKept() = assertEquals("외2명", leadCreator("외2명"))

    @Test
    fun creatorFromRegularReelDescription() = assertEquals(
        "peterang8659",
        creatorFromMediaDescription("peterang8659님이 만든 릴스입니다. 재생하거나 일시 중지하려면 두 번 누르세요."),
    )

    @Test
    fun creatorFromAdDescription() = assertEquals(
        "daelim_univ",
        creatorFromMediaDescription("daelim_univ님의 광고 릴스. 재생하거나 일시 중지하려면 두 번 누르세요."),
    )

    @Test
    fun collabDescriptionFeedsLeadCreator() = assertEquals(
        "a.b",
        creatorFromMediaDescription("a.b님과 c.d님이 만든 릴스입니다.")?.let(::leadCreator),
    )

    @Test
    fun unrelatedDescriptionIsNull() = assertNull(creatorFromMediaDescription("Test ClipsSingleImageAdsComponent"))
}
