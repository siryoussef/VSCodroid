package com.vscodroid.storage

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Maps an `ACTION_OPEN_DOCUMENT_TREE` result URI to the real POSIX path on the device
 * when All Files Access is granted, so the VS Code server can open it without copying.
 *
 * ## How it works
 *
 * Android's ExternalStorageProvider encodes the picked directory as a document ID of the
 * form `primary:Documents/myRepo` (internal storage) or `<volume>:<path>` (SD card /
 * USB drive). Those IDs map 1:1 to POSIX paths under `/storage/emulated/<user>/…` or
 * `/storage/<volume>/…`. [resolve] extracts that path and verifies it is a real
 * directory before returning it.
 *
 * ## When it falls back to null
 *
 * Any URI that is not from ExternalStorageProvider (Downloads provider, a cloud-storage
 * DocumentsUI backend, a network share, etc.) cannot be safely represented as a POSIX
 * path, so null is returned and the caller uses the SAF mirror as before.  The authority
 * `com.android.externalstorage.documents` is the stable public contract since Android 4.4
 * (KitKat); it has not changed through Android 16 at time of writing.
 *
 * ## User slot
 *
 * `/storage/emulated/<user>` – Android uses the numeric user slot from the
 * `DocumentsContract.Document.COLUMN_DOCUMENT_ID` value itself only for the `primary`
 * volume. We resolve through `/storage/emulated/0` for primary (the owner user, which is
 * the only user context an app normally runs in), so multi-user edge cases remain
 * consistent. Secondary users running the app in their own session correctly receive the
 * `primary:…` ID pointing at their own emulated volume, and Android resolves `/storage/
 * emulated/0` to that user's own tree from within their process; the path is safe.
 */
object DirectPath {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Returns the real filesystem path for a SAF tree URI, or `null` when the URI cannot
     * be safely expressed as one (wrong authority, malformed document ID, path does not
     * exist, or All Files Access is not active).
     *
     * The [exists] predicate is injected so that tests can exercise the logic without a
     * real filesystem.
     */
    fun resolve(
        treeUri: Uri,
        exists: (String) -> Boolean = { java.io.File(it).isDirectory },
    ): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null

        // getTreeDocumentId(uri) returns e.g. "primary:Documents/notes" or
        // "1234-5678:Projects".
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null

        val colon = docId.indexOf(':')
        if (colon < 0) return null

        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)       // may be empty at volume root

        val root = when (volume.lowercase()) {
            "primary" -> "/storage/emulated/0"
            else -> "/storage/$volume"                  // removable SD / USB
        }

        val path = if (relative.isEmpty()) root else "$root/$relative"
        return path.takeIf(exists)
    }

    /**
     * Converts an intent URI carrying a `content://` path (e.g. from an "Open with…"
     * file-manager handoff) to a real path usable by the VS Code server.
     *
     * Returns `null` for URIs that are not ExternalStorageProvider document trees.
     */
    fun resolveContentUri(
        uri: Uri,
        exists: (String) -> Boolean = { java.io.File(it).exists() },
    ): String? {
        // Intent ACTION_VIEW from a file manager may carry either:
        //   content://com.android.externalstorage.documents/document/primary%3ADocuments%2Ffoo
        // or a plain file:// URI. We handle both.
        return when (uri.scheme) {
            "file" -> uri.path?.takeIf(exists)
            "content" -> {
                if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrNull() ?: return null
                val colon = docId.indexOf(':')
                if (colon < 0) return null
                val volume = docId.substring(0, colon)
                val relative = docId.substring(colon + 1)
                val root = if (volume.equals("primary", ignoreCase = true))
                    "/storage/emulated/0" else "/storage/$volume"
                val path = if (relative.isEmpty()) root else "$root/$relative"
                path.takeIf(exists)
            }
            else -> null
        }
    }
}
