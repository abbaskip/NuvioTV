package com.nuvio.tv.livetv.ui

internal const val GUIDE_LEFT_HOLD_MS = 500L

/** Respect the platform/remote timeout as well as remotes that only send Down and Up. */
internal fun isGuideLeftHold(elapsedMs: Long, reportedLongPress: Boolean): Boolean =
    reportedLongPress || elapsedMs >= GUIDE_LEFT_HOLD_MS
