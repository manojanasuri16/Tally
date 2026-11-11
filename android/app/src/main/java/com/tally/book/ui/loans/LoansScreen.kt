package com.tally.book.ui.loans

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.tally.book.core.t
import com.tally.book.ui.Routes
import com.tally.book.ui.ScreenPadding
import com.tally.book.ui.components.*

/** Registers the screens: the root (LoansScreen) now; loan detail, family and the rest are added here as they are built. */
fun NavGraphBuilder.loansGraph() {
    composable(Routes.LOANS) { LoansScreen() }
}

/** The root screen. A placeholder for now: the kit's large title and one grouped card. */
@Composable
fun LoansScreen() {
    NavScreen(t("app.name", "Tally"), back = null, largeTitle = true) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            AGroup {
                item { NoteRow(t("home.empty", "Nothing here yet")) }
            }
        }
    }
}
