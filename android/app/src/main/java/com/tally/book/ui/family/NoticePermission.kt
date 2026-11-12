package com.tally.book.ui.family

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tally.book.sync.Notices

/*
 * The hook for Android 13+'s notification permission, for the one join-request notice (sync/Notices.kt). Not asked by
 * itself: the Family screens call the returned function at a moment that explains it (for example when this phone becomes
 * an admin, or from a "Tell me when someone asks to join" row). It does nothing when the permission is not needed or
 * already granted, and calls [onResult] with whether notices can be shown now.
 */
@Composable
fun rememberNoticePermission(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onResult(granted) }
    return remember(launcher) {
        {
            if (!Notices.needsPermission(ctx)) onResult(true)
            else launcher.launch(Notices.PERMISSION)
        }
    }
}
