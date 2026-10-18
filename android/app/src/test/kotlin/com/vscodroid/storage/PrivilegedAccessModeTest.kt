package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * That [PrivilegedAccess.modeFor] is the whole of the storage-mode decision.
 *
 * The direct path exists only when All Files Access is granted: it bypasses the SAF
 * mirror, which removes the 50 MB file cap, the `.git`/`node_modules` exclusions and the
 * stale-on-reopen limitation. Without it the mirror is the only correct answer, because
 * the VS Code server cannot see files another app created on shared storage.
 *
 * Detection itself (`Environment.isExternalStorageManager()`, `Shizuku.pingBinder()`, the
 * `su` probe) is platform state no JVM test can arrange, so what is pinned here is the
 * mapping and nothing around it. The cost of that line is deliberate: a test that mocked
 * the platform calls would measure the mock.
 */
class PrivilegedAccessModeTest {

    @Test
    fun `all files access selects the direct path`() {
        assertEquals(
            WorkspaceStorageMode.DIRECT,
            PrivilegedAccess.modeFor(allFilesAccess = true),
            "with All Files Access the server can open the real path directly; mirroring " +
                "it would reintroduce every limitation the permission removes",
        )
    }

    @Test
    fun `without all files access the mirror is used`() {
        assertEquals(
            WorkspaceStorageMode.SAF_MIRROR,
            PrivilegedAccess.modeFor(allFilesAccess = false),
            "without All Files Access the server cannot see other apps' files on shared " +
                "storage, so the SAF mirror with write-back is the only correct route",
        )
    }

    @Test
    fun `the state's workspace mode follows its own all-files flag`() {
        assertEquals(
            WorkspaceStorageMode.DIRECT,
            PrivilegedAccessState(
                allFilesAccess = true,
                shizukuBackend = ShizukuBackend.ADB,
                rootShellPresent = false,
            ).workspaceMode,
        )
        assertEquals(
            WorkspaceStorageMode.SAF_MIRROR,
            PrivilegedAccessState(
                allFilesAccess = false,
                shizukuBackend = ShizukuBackend.ROOT,
                rootShellPresent = true,
            ).workspaceMode,
            "a root or Shizuku identity alone must not switch the mode: the direct path " +
                "is gated on All Files Access, which those identities can GRANT but which " +
                "having them does not imply",
        )
    }
}
