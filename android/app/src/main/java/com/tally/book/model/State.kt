package com.tally.book.model

import com.tally.book.core.resetUidSeq

/**
 * Puts every piece of model-level state that is not in DB back to how a fresh start has it (and between tests).
 * A model file that keeps top-level state (a cache, a flag) adds its reset call here.
 */
fun resetModelState() {
    resetUidSeq()
}
