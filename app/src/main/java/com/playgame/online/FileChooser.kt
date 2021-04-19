package com.playgame.online

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class FileChooseClient(private val activity: ActivityChoser) : WebChromeClient() {
    override fun onShowFileChooser(v: WebView?, fpc: ValueCallback<Array<Uri>>?, fcp: FileChooserParams): Boolean {
        if (activity.uploadMessage != null) {
            activity.uploadMessage?.onReceiveValue(null)
            activity.uploadMessage = null
        }

        activity.uploadMessage = fpc

        val intent: Intent = fcp.createIntent()
        try {
            activity.startActivityForResult(intent, ActivityChoser.REQUEST_SELECT_FILE)
            (activity as Activity).overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } catch (e: ActivityNotFoundException) {
            activity.uploadMessage = null
            return false
        }

        return true
    }

    interface ActivityChoser {
        companion object {
            const val REQUEST_SELECT_FILE = 100;
        }

        var uploadMessage: ValueCallback<Array<Uri>>?

        fun startActivityForResult(intent: Intent, req: Int)

    }
}
