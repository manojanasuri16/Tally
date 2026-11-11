package com.tally.book.model

import com.tally.book.core.*

/** A new, empty book. Everything else in the DESIGN.md shape (me, family, loans, ...) is created lazily by the code that first needs it. */
fun freshDb(): Obj = obj("v" to 1)

/** Fills what a saved book may lack, without touching anything it has. Called by Ledger.start() after the saved book is merged in. */
fun ensureBookShape(db: Obj) {
    if (!db.containsKey("v")) db["v"] = 1.0
    for (k in listOf("loans", "payments", "files", "members")) db.aa(k)
    val sync = db.oo("sync")
    if (!sync.containsKey("cursor")) sync["cursor"] = 0.0
    sync.oo("dirty"); sync.oo("blobsUp")
}
