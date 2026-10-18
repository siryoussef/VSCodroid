package com.vscodroid.storage

import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That [DirectPath.resolve] maps SAF tree URIs to POSIX paths correctly and falls back
 * to null for anything that cannot be expressed as one.
 *
 * [Uri] and [DocumentsContract] are Android stubs under the JVM test runner; we mock the
 * two static calls we rely on so the logic under test is pure Kotlin string manipulation
 * rather than framework dispatch.
 */
class DirectPathResolveTest {

    private val authority = "com.android.externalstorage.documents"

    /** Build a fake tree URI whose authority the test chooses. */
    private fun treeUri(authority: String, docId: String = "primary:") = Uri.parse(
        // No Uri.encode here: it is an unmocked android.jar stub under the JVM
        // runner and would throw before the assertion is reached. The docId is
        // test data with no character that needs encoding for this test's purpose,
        // since only the authority is ever read back by the code under test.
        "content://$authority/tree/$docId"
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class, DocumentsContract::class)
        // Uri.parse delegates to android.net.Uri which is a stub; mock the authority
        // getter instead so every treeUri() call returns a predictable value.
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            val fakeUri = io.mockk.mockk<Uri>()
            // authority = the part between "content://" and the first "/"
            val auth = raw.removePrefix("content://").substringBefore("/")
            every { fakeUri.authority } returns auth
            every { fakeUri.scheme } returns "content"
            every { fakeUri.path } returns null
            fakeUri
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun resolve(docId: String, authority: String = this.authority): String? {
        val uri = treeUri(authority, docId)
        every { DocumentsContract.getTreeDocumentId(uri) } returns docId
        return DirectPath.resolve(uri, exists = { true })
    }

    // ── primary volume ────────────────────────────────────────────────────────

    @Test
    fun `primary root maps to storage emulated 0`() {
        assertEquals("/storage/emulated/0", resolve("primary:"))
    }

    @Test
    fun `primary sub-path maps correctly`() {
        assertEquals("/storage/emulated/0/Documents/myRepo", resolve("primary:Documents/myRepo"))
    }

    @Test
    fun `PRIMARY uppercase is treated the same as lowercase`() {
        assertEquals("/storage/emulated/0/Work", resolve("PRIMARY:Work"))
    }

    // ── removable volumes ─────────────────────────────────────────────────────

    @Test
    fun `SD card volume maps to storage volume`() {
        assertEquals("/storage/1234-5678/Projects", resolve("1234-5678:Projects"))
    }

    @Test
    fun `SD card root maps to storage volume root`() {
        assertEquals("/storage/ABCD-1234", resolve("ABCD-1234:"))
    }

    // ── existence check ───────────────────────────────────────────────────────

    @Test
    fun `returns null when path does not exist`() {
        val uri = treeUri(authority, "primary:NoSuchDir")
        every { DocumentsContract.getTreeDocumentId(uri) } returns "primary:NoSuchDir"
        assertNull(DirectPath.resolve(uri, exists = { false }))
    }

    // ── wrong authority ───────────────────────────────────────────────────────

    @Test
    fun `non-ExternalStorageProvider authority returns null`() {
        assertNull(resolve("primary:Documents", authority = "com.android.providers.downloads.documents"))
    }

    @Test
    fun `Google Drive authority returns null`() {
        assertNull(resolve("root:Documents", authority = "com.google.android.apps.docs.storage"))
    }

    // ── malformed document ID ─────────────────────────────────────────────────

    @Test
    fun `document ID with no colon returns null`() {
        val uri = treeUri(authority, "badformat")
        every { DocumentsContract.getTreeDocumentId(uri) } returns "badformat"
        assertNull(DirectPath.resolve(uri, exists = { true }))
    }

    @Test
    fun `getTreeDocumentId throwing returns null`() {
        val uri = treeUri(authority, "primary:x")
        every { DocumentsContract.getTreeDocumentId(uri) } throws IllegalArgumentException("not a tree")
        assertNull(DirectPath.resolve(uri, exists = { true }))
    }
}

/**
 * That [DirectPath.resolveContentUri] handles both `file://` and `content://` URIs from
 * external file managers, falling back to null for unrecognised schemes or authorities.
 */
class DirectPathContentUriTest {

    private val authority = "com.android.externalstorage.documents"

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class, DocumentsContract::class)
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            val fakeUri = io.mockk.mockk<Uri>()
            every { fakeUri.scheme } returns raw.substringBefore("://")
            every { fakeUri.authority } returns raw.removePrefix("content://").substringBefore("/")
            every { fakeUri.path } returns null
            fakeUri
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `file URI returns its path when it exists`() {
        val uri = io.mockk.mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.path } returns "/storage/emulated/0/myProject"
        assertEquals(
            "/storage/emulated/0/myProject",
            DirectPath.resolveContentUri(uri, exists = { true }),
        )
    }

    @Test
    fun `file URI returns null when path does not exist`() {
        val uri = io.mockk.mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.path } returns "/storage/emulated/0/gone"
        assertNull(DirectPath.resolveContentUri(uri, exists = { false }))
    }

    @Test
    fun `content URI from ExternalStorageProvider resolves correctly`() {
        val uri = io.mockk.mockk<Uri>()
        every { uri.scheme } returns "content"
        every { uri.authority } returns authority
        every { DocumentsContract.getDocumentId(uri) } returns "primary:Repos/cool"
        assertEquals(
            "/storage/emulated/0/Repos/cool",
            DirectPath.resolveContentUri(uri, exists = { true }),
        )
    }

    @Test
    fun `content URI from Downloads provider returns null`() {
        val uri = io.mockk.mockk<Uri>()
        every { uri.scheme } returns "content"
        every { uri.authority } returns "com.android.providers.downloads.documents"
        assertNull(DirectPath.resolveContentUri(uri, exists = { true }))
    }

    @Test
    fun `unknown scheme returns null`() {
        val uri = io.mockk.mockk<Uri>()
        every { uri.scheme } returns "ftp"
        assertNull(DirectPath.resolveContentUri(uri, exists = { true }))
    }
}
