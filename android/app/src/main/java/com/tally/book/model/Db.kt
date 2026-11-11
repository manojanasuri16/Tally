package com.tally.book.model

import com.tally.book.core.*

/*
 * The book: DB is a JSON tree (Obj/Arr, every number a Double, unknown fields round-trip), read and written
 * directly by the model layer. Only the main thread touches it (see data/Ledger.kt). Shape: docs/DESIGN.md.
 */

/** Called on every read of [DB]. `data/Ledger` sets it so Compose notices the read; the model itself stays free of Compose. */
@JvmField var onDbRead: (() -> Unit)? = null
private var dbValue: Obj = freshDb()
var DB: Obj
    get() { onDbRead?.invoke(); return dbValue }
    set(value) { dbValue = value }
