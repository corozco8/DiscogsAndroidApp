package com.example.discogsandroidapp.data

import org.junit.Assert.*
import org.junit.Test

class ComparablePriceRecommendationTest {
    private val vg = "Very Good (VG)"
    private val vgp = "Very Good Plus (VG+)"
    private val nm = "Near Mint (NM or M-)"
    private val mint = "Mint (M)"

    @Test fun lowestQualifyingAskWinsOverTheMedian() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(1.0, 18.0, 20.0, 22.0, 100.0)))
        assertEquals(1.0, summary.recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun cheaperEligibleSleeveWinsOverAnExactSleeveMatch() {
        val summary = ReleasePriceSummary(activeMediaSleevePriceSamples = mapOf(
            "$vgp||$vgp" to listOf(30.0, 40.0),
            "$vgp||$vg" to listOf(18.0, 20.0),
            "$vgp||Poor (P)" to listOf(0.50, 1.0)))
        assertEquals(18.0, summary.currentListingPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun lowestEligibleAskExcludesPoorSleeves() {
        val summary = ReleasePriceSummary(activeMediaSleevePriceSamples = mapOf(
            "$vgp||$vgp" to listOf(18.0),
            "$vgp||$nm" to listOf(20.0, 22.0, 24.0),
            "$vgp||Good (G)" to listOf(1.0, 2.0)))
        assertEquals(18.0, summary.currentListingPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun invalidSamplesAndOneComparableCannotCreateALiveRecommendation() {
        assertNull(lowestComparableAskingPrice(listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 10.0)))
        assertEquals(10.0, lowestComparableAskingPrice(listOf(10.0, 20.0, Double.NaN))!!, 0.0)
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(10.0)),
            activeMediaListingCounts = mapOf(vgp to 10), activeMediaLowestPrices = mapOf(vgp to 1.0))
        assertNull(summary.currentListingPriceFor(vgp))
    }

    @Test fun aDeliberatelyPoorSleeveRestoresTheLowestMediaPrice() {
        val summary = ReleasePriceSummary(activeMediaPriceSamples = mapOf(vgp to listOf(1.0, 20.0, 30.0)),
            activeMediaSleevePriceSamples = mapOf("$vgp||No Cover" to listOf(8.0, 10.0)))
        assertEquals(1.0, summary.currentListingPriceFor(vgp, "No Cover")!!, 0.0)
    }

    @Test fun missingVgPlusInterpolatesLowestLiveAnchors() {
        val summary = ReleasePriceSummary(isAlbumRelease = false, activeMediaPriceSamples = mapOf(
            nm to listOf(20.0, 30.0, 100.0), vg to listOf(8.0, 10.0, 50.0)))
        assertEquals(14.0, summary.recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun fallbackIgnoresAnUnrelatedLowestAskingPriceAndUsesTheExactGrade() {
        val summary = ReleasePriceSummary(numForSale = 100, priceSuggestions = PriceSuggestions(
            veryGoodPlus = PriceSuggestionValue(10.0), nearMint = PriceSuggestionValue(15.0), mint = PriceSuggestionValue(21.0)))
        for (lowest in listOf(0.01, 1000.0)) {
            val changed = summary.copy(lowestAskingPrice = lowest)
            assertEquals(10.0, changed.recommendedPriceFor(vgp)!!, 0.0)
            assertEquals(15.0, changed.recommendedPriceFor(nm)!!, 0.0)
            assertEquals(21.0, changed.recommendedPriceFor(mint)!!, 0.0)
        }
    }

    @Test fun fallbackInterpolatesMissingApiGradeAndRejectsContradictoryAnchors() {
        val summary = ReleasePriceSummary(priceSuggestions = PriceSuggestions(
            veryGood = PriceSuggestionValue(8.0), nearMint = PriceSuggestionValue(20.0)))
        assertEquals(14.0, summary.recommendedPriceFor(vgp)!!, 0.0)
        assertNull(summary.copy(priceSuggestions = PriceSuggestions(
            veryGood = PriceSuggestionValue(20.0), nearMint = PriceSuggestionValue(8.0))).recommendedPriceFor(vgp))
    }

    @Test fun fallbackSleeveAdjustmentsDoNotApplyToNonAlbums() {
        val summary = ReleasePriceSummary(priceSuggestions = PriceSuggestions(veryGoodPlus = PriceSuggestionValue(20.0)))
        assertEquals(20.0, summary.recommendedPriceFor(vgp, vgp)!!, 0.0)
        assertEquals(22.0, summary.recommendedPriceFor(vgp, mint)!!, 0.0)
        assertEquals(15.0, summary.recommendedPriceFor(vgp, "No Cover")!!, 0.0)
        assertEquals(20.0, summary.copy(isAlbumRelease = false).recommendedPriceFor(vgp, "No Cover")!!, 0.0)
    }

    @Test fun unsupportedCurrencyInvalidAmountsAndMissingDataCannotBecomeDollarRecommendations() {
        assertNull(ReleasePriceSummary().recommendedPriceFor(vgp))
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(ReleasePriceSummary(priceSuggestions = PriceSuggestions(
                veryGoodPlus = PriceSuggestionValue(amount))).recommendedPriceFor(vgp))
        }
        assertNull(ReleasePriceSummary(priceSuggestions = PriceSuggestions(
            veryGoodPlus = PriceSuggestionValue(20.0, "EUR"))).recommendedPriceFor(vgp))
        assertNull(ReleasePriceSummary(currency = "EUR", median = 20.0).recommendedPriceFor(vgp))
        assertNull(ReleasePriceSummary(priceSuggestions = PriceSuggestions(
            veryGoodPlus = PriceSuggestionValue(20.0))).recommendedPriceFor("Not Graded"))
    }

    @Test fun priceGuideMedianAndSingleApiGradeStillProvideAnEstimateWithoutLiveData() {
        assertEquals(12.0, ReleasePriceSummary(median = 20.0).recommendedPriceFor(vg)!!, 0.0)
        assertEquals(12.0, ReleasePriceSummary(priceSuggestions = PriceSuggestions(
            nearMint = PriceSuggestionValue(20.0))).recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun singleTenDollarVgListingEstimatesNearMintWithoutIntermediateRounding() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vg to listOf(10.0)))
        assertEquals(27.78, summary.liveGradeEstimateFor(nm)!!.price, 0.0)
        assertEquals(16.67, summary.liveGradeEstimateFor(vgp)!!.price, 0.0)
        assertEquals(vg, summary.liveGradeEstimateFor(nm)!!.sourceCondition)
        assertNull(summary.currentListingPriceFor(nm))
        assertEquals(27.78, summary.recommendedPriceFor(nm)!!, 0.0)
    }

    @Test fun singleTwentyFourDollarNearMintListingEstimatesVeryGood() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(nm to listOf(24.0)))
        assertEquals(8.64, summary.liveGradeEstimateFor(vg)!!.price, 0.0)
        assertEquals(14.40, summary.liveGradeEstimateFor(vgp)!!.price, 0.0)
        assertNull(summary.currentListingPriceFor(vg))
    }

    @Test fun matchingSingletonRemainsAnEstimateRatherThanATrustedLivePrice() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(10.0)))
        assertNull(summary.currentListingPriceFor(vgp))
        assertEquals(10.0, summary.liveGradeEstimateFor(vgp)!!.price, 0.0)
    }

    @Test fun singleListingsOnBothSidesCanInterpolateAMissingGrade() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(nm to listOf(20.0), vg to listOf(8.0)))
        val estimate = summary.liveGradeEstimateFor(vgp)!!
        assertEquals(14.0, estimate.price, 0.0)
        assertEquals(vg, estimate.secondSourceCondition)
    }

    @Test fun aLoneMatchingListingWinsOverAnEstimateFromAnotherGrade() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vg to listOf(8.0, 10.0), vgp to listOf(12.0), nm to listOf(30.0)))
        assertEquals(30.0, summary.liveGradeEstimateFor(nm)!!.price, 0.0)
        assertEquals(nm, summary.liveGradeEstimateFor(nm)!!.sourceCondition)
        assertEquals(12.0, summary.liveGradeEstimateFor(vgp)!!.price, 0.0)
        assertEquals(8.0, summary.currentListingPriceFor(vg)!!, 0.0)
    }

    @Test fun singleListingFallbackStillRejectsPoorAlbumSleeves() {
        val summary = ReleasePriceSummary(
            priceSuggestions = PriceSuggestions(nearMint = PriceSuggestionValue(40.0)),
            activeMediaPriceSamples = mapOf(vg to listOf(10.0)),
            activeMediaSleevePriceSamples = mapOf("$vg||Poor (P)" to listOf(10.0)))
        assertNull(summary.liveGradeEstimateFor(nm, vgp))
        assertEquals(40.0, summary.recommendedPriceFor(nm, vgp)!!, 0.0)
        assertEquals(27.78, summary.liveGradeEstimateFor(nm, "No Cover")!!.price, 0.0)
    }

    @Test fun contradictoryOrTooCloseSingletonAnchorsDoNotReplaceTheApiFallback() {
        for (vgPrice in listOf(19.0, 25.0)) {
            val summary = ReleasePriceSummary(isAlbumRelease = false,
                priceSuggestions = PriceSuggestions(veryGoodPlus = PriceSuggestionValue(14.0)),
                activeMediaPriceSamples = mapOf(nm to listOf(20.0), vg to listOf(vgPrice)))
            assertNull(summary.liveGradeEstimateFor(vgp))
            assertEquals(14.0, summary.recommendedPriceFor(vgp)!!, 0.0)
        }
    }

    @Test fun singleListingNeedsAValidPriceEvenWhenCountFieldsArePresent() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            val summary = ReleasePriceSummary(isAlbumRelease = false,
                activeMediaPriceSamples = mapOf(vg to listOf(amount)),
                activeMediaLowestPrices = mapOf(vg to amount), activeMediaListingCounts = mapOf(vg to 1))
            assertNull(summary.liveGradeEstimateFor(nm))
        }
    }

    @Test fun legacyCachedSingletonCanBeUsedWithoutReloadingButNeedsACount() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaLowestPrices = mapOf(vg to 10.0), activeMediaListingCounts = mapOf(vg to 1))
        assertEquals(27.78, summary.liveGradeEstimateFor(nm)!!.price, 0.0)
        assertNull(summary.copy(activeMediaListingCounts = emptyMap()).liveGradeEstimateFor(nm))
    }
}
