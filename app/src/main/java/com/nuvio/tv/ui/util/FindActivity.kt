package com.nuvio.tv.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The Activity behind a (possibly wrapped) Compose context, or null outside one. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
