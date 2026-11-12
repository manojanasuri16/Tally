package com.tally.book

import android.app.Application
import com.tally.book.core.DevicePrefs
import com.tally.book.core.I18n
import com.tally.book.data.Backups
import com.tally.book.data.DueNotices
import com.tally.book.data.FileStore
import com.tally.book.data.Ledger
import com.tally.book.sync.Notices
import com.tally.book.ui.family.installSyncHooks
import com.tally.book.ui.lock.Bio
import com.tally.book.ui.lock.PhoneLock
import com.tally.book.widget.NextPaymentData

class TallyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DevicePrefs.init(this)
        I18n.init(this, DevicePrefs.get("tally-lang") ?: "en")
        // before the book or the files are touched: finishes a restore that was cut short
        Backups.init(this)
        Ledger.init(this)
        FileStore.init(this)
        // this phone's own secret for Tally's own lock (under an Android Keystore key): a PIN opens the book only here
        com.tally.book.data.Crypto.device = com.tally.book.ui.lock.DeviceKey(this)
        Bio.init(this)
        PhoneLock.init(this)
        installSyncHooks()
        Notices.init(this)
        DueNotices.init(this)
        NextPaymentData.init(this)
        // stored documents follow the lock (encrypted while a lock is set)
        Ledger.onStarted = { FileStore.filesAfterPersist() }
    }
}
