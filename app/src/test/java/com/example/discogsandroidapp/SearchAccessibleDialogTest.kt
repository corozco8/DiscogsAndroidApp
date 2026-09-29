package com.example.discogsandroidapp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAccessibleDialogTest {
    private val search = Rect(40f, 60f, 360f, 130f)

    @Test fun saveTapInOverlappingDialogNeverFocusesSearch() {
        // A taller form / visible IME puts the dialog's Save button over Search.
        assertFalse(shouldFocusSearchBehindDialog(
            Offset(320f, 110f), search, Rect(20f, 90f, 380f, 500f), true
        ))
    }

    @Test fun exposedSearchStillWorks() {
        assertTrue(shouldFocusSearchBehindDialog(
            Offset(200f, 80f), search, Rect(20f, 150f, 380f, 500f), true
        ))
    }

    @Test fun onlyTheExposedPartOfSearchAcceptsTaps() {
        val dialog = Rect(20f, 100f, 380f, 500f)
        assertTrue(shouldFocusSearchBehindDialog(Offset(200f, 99f), search, dialog, true))
        assertFalse(shouldFocusSearchBehindDialog(Offset(200f, 100f), search, dialog, true))
    }

    @Test fun movingDialogWithKeyboardChangesWhoOwnsTheTap() {
        val tap = Offset(320f, 110f)
        assertTrue(shouldFocusSearchBehindDialog(tap, search, Rect(20f, 180f, 380f, 590f), true))
        assertFalse(shouldFocusSearchBehindDialog(tap, search, Rect(20f, 70f, 380f, 480f), true))
    }

    @Test fun submittingDisablesSearchHandoff() {
        assertFalse(shouldFocusSearchBehindDialog(
            Offset(200f, 80f), search, Rect(20f, 150f, 380f, 500f), false
        ))
    }

    @Test fun unknownOrEmptyLayoutNeverDismissesTheForm() {
        val tap = Offset(200f, 80f)
        assertFalse(shouldFocusSearchBehindDialog(tap, search, null, true))
        assertFalse(shouldFocusSearchBehindDialog(tap, search, Rect.Zero, true))
        assertFalse(shouldFocusSearchBehindDialog(tap, null, Rect(20f, 150f, 380f, 500f), true))
    }

    @Test fun unrelatedOutsideTapDoesNotFocusSearch() {
        assertFalse(shouldFocusSearchBehindDialog(
            Offset(390f, 140f), search, Rect(20f, 150f, 380f, 500f), true
        ))
    }
}
