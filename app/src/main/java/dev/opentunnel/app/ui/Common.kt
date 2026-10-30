package dev.opentunnel.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

internal fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(context, "No browser found, link copied", Toast.LENGTH_SHORT).show()
    }
}

internal fun toast(context: Context, text: String, always: Boolean = false) {
    // Android 13 and newer show their own confirmation for clipboard writes.
    if (always || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
