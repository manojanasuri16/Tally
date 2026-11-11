package com.tally.book

import android.app.Application
import com.tally.book.core.DevicePrefs
import com.tally.book.core.I18n
import com.tally.book.data.FileStore
import com.tally.book.data.Ledger
import com.tally.book.ui.lock.Bio

class TallyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DevicePrefs.init(this)
        I18n.init(this, DevicePrefs.get("tally-lang") ?: "en")
        Ledger.init(this)
        FileStore.init(this)
        Bio.init(this)
        // stored documents follow the lock (encrypted while a lock is set)
        Ledger.onStarted = { FileStore.filesAfterPersist() }
    }
}
