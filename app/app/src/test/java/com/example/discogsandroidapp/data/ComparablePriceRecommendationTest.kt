package com.example.discogsandroidapp.data

import org.junit.Assert.*
import org.junit.Test

class ComparablePriceRecommendationTest {
    private val vg = "Very Good (VG)"
    private val vgp = "Very Good Plus (VG+)"
    private val nm = "Near Mint (NM or M-)"
    private val mint = "Mint (M)"

    @Test fun secondCheapestEligibleListingWinsOverTheLowestAndMedian() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(1.0, 18.0, 20.0, 22.0, 100.0)))
        assertEquals(18.0, summary.recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun secondCheapestAcrossAllEligibleSleevesWinsOverExactPair() {
        val summary = ReleasePriceSummary(activeMediaSleevePriceSamples = mapOf(
            "$vgp||$vgp" to listOf(30.0, 40.0),
            "$vgp||$vg" to listOf(18.0, 20.0),
            "$vgp||Poor (P)" to listOf(0.50, 1.0)))
        assertEquals(20.0, summary.currentListingPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun secondCheapestEligibleAskExcludesPoorSleeves() {
        val summary = ReleasePriceSummary(activeMediaSleevePriceSamples = mapOf(
            "$vgp||$vgp" to listOf(18.0),
            "$vgp||$nm" to listOf(20.0, 22.0, 24.0),
            "$vgp||Good (G)" to listOf(1.0, 2.0)))
        assertEquals(20.0, summary.currentListingPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun invalidSamplesAndSingletonUsesItsOnlyValidPrice() {
        assertEquals(10.0, preferredComparableAskingPrice(listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 10.0))!!, 0.0)
        assertEquals(20.0, preferredComparableAskingPrice(listOf(10.0, 20.0, Double.NaN))!!, 0.0)
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(10.0)),
            activeMediaListingCounts = mapOf(vgp to 10), activeMediaLowestPrices = mapOf(vgp to 1.0))
        assertEquals(10.0, summary.currentListingPriceFor(vgp)!!, 0.0)
    }

    @Test fun aDeliberatelyPoorSleeveUsesSecondCheapestMediaPrice() {
        val summary = ReleasePriceSummary(activeMediaPriceSamples = mapOf(vgp to listOf(1.0, 20.0, 30.0)),
            activeMediaSleevePriceSamples = mapOf("$vgp||No Cover" to listOf(8.0, 10.0)))
        assertEquals(20.0, summary.currentListingPriceFor(vgp, "No Cover")!!, 0.0)
    }

    @Test fun missingVgPlusInterpolatesLowestLiveAnchors() {
        val summary = ReleasePriceSummary(isAlbumRelease = false, activeMediaPriceSamples = mapOf(
            nm to listOf(20.0, 30.0, 100.0), vg to listOf(8.0, 10.0, 50.0)))
        assertEquals(20.0, summary.recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun deepMarketCanUseHistoricalGuideToRescueAnImplausiblyLowVgPlusFloor() {
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 10.0,
            numForSale = 100,
            isAlbumRelease = false,
            priceSuggestions = PriceSuggestions(
                veryGoodPlus = PriceSuggestionValue(100.0),
                nearMint = PriceSuggestionValue(150.0),
                mint = PriceSuggestionValue(210.0)
            )
        )

        // VG+ gets a log-space rescue because a deep market plus a 4x+ guide gap
        // strongly suggests that the overall floor is a lower-grade outlier.
        assertEquals(36.37, summary.recommendedPriceFor(vgp)!!, 0.0)

        // NM/M keep the conservative floor-derived path until the comparison data
        // supports broadening the rescue to those grades.
        assertEquals(15.21, summary.recommendedPriceFor(nm)!!, 0.0)
        assertEquals(17.49, summary.recommendedPriceFor(mint)!!, 0.0)
    }

    @Test fun sparseMarketDoesNotLetAStaleVgPlusGuideOverrideTheCurrentFloor() {
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 2.41,
            numForSale = 20,
            isAlbumRelease = false,
            priceSuggestions = PriceSuggestions(
                veryGoodPlus = PriceSuggestionValue(29.21)
            )
        )

        assertEquals(3.19, summary.recommendedPriceFor(vgp)!!, 0.0)
    }

    @Test fun historicalLowAboveFloorPartiallyRescuesUnderpricedVg() {
        // Boston: its cheapest overall listing is well below the historical low,
        // while the VG guide independently signals an unusually low current floor.
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 3.00, low = 8.82, numForSale = 51,
            priceSuggestions = PriceSuggestions(veryGood = PriceSuggestionValue(79.35))
        )
        assertEquals(5.71, summary.fallbackRecommendedPriceFor(vg, vg)!!, 0.0)
    }

    @Test fun guideLowAtOrBelowFloorPreservesCloseVgPrices() {
        // Gabor Szabo: the unadjusted floor estimate is within a dollar of live.
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 9.99, low = 4.30, numForSale = 31,
            priceSuggestions = PriceSuggestions(veryGood = PriceSuggestionValue(38.67))
        )
        assertEquals(10.92, summary.fallbackRecommendedPriceFor(vg, vg)!!, 0.0)
    }

    @Test fun sparseMarketDoesNotApplyLowGuideRescue() {
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 3.00, low = 8.82, numForSale = 5,
            priceSuggestions = PriceSuggestions(veryGood = PriceSuggestionValue(79.35))
        )
        assertEquals(3.28, summary.fallbackRecommendedPriceFor(vg, vg)!!, 0.0)
    }

    @Test fun marketFloorCalibrationStillAppliesAlbumSleeveAdjustment() {
        val summary = ReleasePriceSummary(
            lowestAskingPrice = 5.0,
            priceSuggestions = PriceSuggestions(veryGoodPlus = PriceSuggestionValue(100.0))
        )

        assertEquals(6.61, summary.fallbackRecommendedPriceFor(vgp, vgp)!!, 0.0)
        assertEquals(6.28, summary.fallbackRecommendedPriceFor(vgp, vg)!!, 0.0)
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

    @Test fun widelyAvailableVgPlusReleasesDoNotGetStaleGuideRescue() {
        // The new comparison file shows that a guide rescue badly overprices
        // common pressings like Supertramp / Genesis with 120+ copies for sale.
        val genesis = ReleasePriceSummary(
            lowestAskingPrice = 3.91, numForSale = 122,
            priceSuggestions = PriceSuggestions(veryGoodPlus = PriceSuggestionValue(23.62))
        )
        assertEquals(5.17, genesis.fallbackRecommendedPriceFor(vgp, vgp)!!, 0.0)

        // Still allow the existing conservative rescue for less-supplied items.
        val doors = ReleasePriceSummary(
            lowestAskingPrice = 10.0, numForSale = 54,
            priceSuggestions = PriceSuggestions(veryGoodPlus = PriceSuggestionValue(133.82))
        )
        assertEquals(42.07, doors.fallbackRecommendedPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun matchingSingletonNowReturnsTheOnlyLiveAskingPrice() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaPriceSamples = mapOf(vgp to listOf(10.0)))
        assertEquals(10.0, summary.currentListingPriceFor(vgp)!!, 0.0)
        assertNull(summary.liveGradeEstimateFor(vgp))
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
        assertEquals(30.0, summary.currentListingPriceFor(nm)!!, 0.0)
        assertNull(summary.liveGradeEstimateFor(nm))
        assertEquals(12.0, summary.currentListingPriceFor(vgp)!!, 0.0)
        assertEquals(10.0, summary.currentListingPriceFor(vg)!!, 0.0)
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
    @Test fun secondCheapestCountsTwoDifferentListingsAtTheSamePrice() {
        assertEquals(10.0, preferredComparableAskingPrice(listOf(10.0, 10.0, 25.0))!!, 0.0)
        assertEquals(19.0, preferredComparableAskingPrice(listOf(20.0, 19.0, 9.0))!!, 0.0)
        assertNull(preferredComparableAskingPrice(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test fun singleQualifyingSleeveListingRemainsLiveAndDoesNotUseExcludedLowPrices() {
        val summary = ReleasePriceSummary(activeMediaSleevePriceSamples = mapOf(
            "$vgp||$vgp" to listOf(25.0),
            "$vgp||Poor (P)" to listOf(1.0, 2.0)))
        assertEquals(25.0, summary.currentListingPriceFor(vgp, vgp)!!, 0.0)
    }

    @Test fun previouslyCachedMinimumCanStillBeReusedWithoutNetworkReload() {
        val summary = ReleasePriceSummary(isAlbumRelease = false,
            activeMediaListingCounts = mapOf(vgp to 3),
            activeMediaLowestPrices = mapOf(vgp to 11.0))
        assertEquals(11.0, summary.currentListingPriceFor(vgp)!!, 0.0)
    }

}
