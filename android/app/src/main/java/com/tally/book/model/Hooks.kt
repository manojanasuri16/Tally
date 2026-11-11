package com.tally.book.model

/*
 * The few places the model has to ask the UI to redraw, save, confirm or
 * tell. The UI (data/Ledger.kt, ui/) fills these in at start-up. Defaults are no-ops, and `ask`
 * answers false, so nothing destructive happens before the UI is there (and in tests).
 * ask/tell are suspending because they wait for the person.
 */
object UiHooks {
    var render: () -> Unit = {}
    var touch: () -> Unit = {}
    var save: () -> Unit = {}
    var ask: suspend (message: String, ok: String?, danger: Boolean) -> Boolean = { _, _, _ -> false }
    var hint: (message: String) -> Unit = {}
    var tell: suspend (message: String) -> Unit = {}
}
