package com.tally.book.ui.loans

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tally.book.ui.components.rememberHaptics
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic

/* Small pieces the loan and payment forms share. */

fun kindIcon(kind: String): ImageVector = when (kind) {
    "bank" -> Icons.Rounded.AccountBalance
    "hand" -> Icons.Rounded.Person
    else -> Icons.Rounded.Diamond
}

/** Keeps digits only ("12a" -> "12"). */
fun digitsOnly(s: String): String = s.filter { it.isDigit() }

/** Keeps digits and at most one point ("1.2.3" -> "1.23"). */
fun decimalOnly(s: String): String {
    var dot = false
    return s.filter { c -> if (c == '.') (!dot).also { if (it) dot = true } else c.isDigit() }
}

/**
 * A form's problem in plain words, in red under the field that asked for it. It brings itself into view (the sheet scrolls to it) and is
 * read out by TalkBack when it appears. [message] null draws nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FormError(message: String?, stamp: Int) {
    if (message == null) return
    val bring = remember { BringIntoViewRequester() }
    val haptics = rememberHaptics()
    LaunchedEffect(stamp) { haptics.reject(); bring.bringIntoView() }
    Text(
        message, Modifier.bringIntoViewRequester(bring).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = Apple.type.callout, color = LocalSemantic.current.negative,
    )
}
