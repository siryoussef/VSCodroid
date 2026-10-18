package com.vscodroid.storage

import android.content.pm.PackageManager
import android.os.Environment
import rikka.shizuku.Shizuku
import java.io.File

/** The access route a workspace uses after the user has selected it. */
enum class WorkspaceStorageMode {
    DIRECT,
    SAF_MIRROR,
}

/** Identity under which Shizuku/Sui is currently serving this app. */
enum class ShizukuBackend {
    UNAVAILABLE,
    NOT_GRANTED,
    ADB,
    ROOT,
    OTHER,
}

/**
 * The opt-in privileges relevant to filesystem access.
 *
 * Detecting a root binary intentionally does not execute it. Root commands require a
 * separate, user-initiated operation; seeing `su` is not consent to use it.
 */
data class PrivilegedAccessState(
    val allFilesAccess: Boolean,
    val shizukuBackend: ShizukuBackend,
    val rootShellPresent: Boolean,
) {
    val workspaceMode: WorkspaceStorageMode
        get() = if (allFilesAccess) WorkspaceStorageMode.DIRECT else WorkspaceStorageMode.SAF_MIRROR
}

object PrivilegedAccess {
    private val rootCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
    )

    fun current(): PrivilegedAccessState = PrivilegedAccessState(
        allFilesAccess = Environment.isExternalStorageManager(),
        shizukuBackend = shizukuBackend(),
        rootShellPresent = rootCandidates.any { File(it).canExecute() },
    )

    internal fun modeFor(allFilesAccess: Boolean): WorkspaceStorageMode =
        if (allFilesAccess) WorkspaceStorageMode.DIRECT else WorkspaceStorageMode.SAF_MIRROR

    private fun shizukuBackend(): ShizukuBackend = try {
        if (!Shizuku.pingBinder()) {
            ShizukuBackend.UNAVAILABLE
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ShizukuBackend.NOT_GRANTED
        } else {
            when (Shizuku.getUid()) {
                0 -> ShizukuBackend.ROOT
                2000 -> ShizukuBackend.ADB
                else -> ShizukuBackend.OTHER
            }
        }
    } catch (_: IllegalStateException) {
        ShizukuBackend.UNAVAILABLE
    }
}