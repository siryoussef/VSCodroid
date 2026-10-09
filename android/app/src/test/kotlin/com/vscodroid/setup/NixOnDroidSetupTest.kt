package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for the bundled Nix-on-Droid terminal environment setup.
 */
class NixOnDroidSetupTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var nativeLibDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val appInfo = ApplicationInfo().apply {
            this.nativeLibraryDir = nativeLibDir.absolutePath
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns appInfo
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    @Test
    fun `setupNixOnDroid writes login launcher and bootstrap script`() {
        FirstRunSetup(context).setupNixOnDroid()

        val loginScript = File(Environment.getNixOnDroidInitPath(context))
        assertTrue(loginScript.isFile, "nix-on-droid-login must exist: $loginScript")
        val loginText = loginScript.readText()
        assertTrue(loginText.contains("PROOT_TMP_DIR"), "login script must configure proot tmpdir")
        assertTrue(loginText.contains("libproot.so"), "login script must reference libproot.so")
        assertTrue(loginText.contains("vscodroid-nix-bootstrap"), "login script must provide guidance for bootstrap")

        val bootstrapScript = File(Environment.getNixBootstrapScriptPath(context))
        assertTrue(bootstrapScript.isFile, "vscodroid-nix-bootstrap must exist: $bootstrapScript")
        val bootstrapText = bootstrapScript.readText()
        assertTrue(bootstrapText.contains("bootstrap-aarch64.zip"), "bootstrap script must download bootstrap zip")
        assertTrue(bootstrapText.contains("SYMLINKS.txt"), "bootstrap script must handle symlinks")
    }

    @Test
    fun `isNixInitialized reflects presence of nix store`() {
        assertFalse(Environment.isNixInitialized(context), "Nix should not be initialized on clean install")

        val storeDir = File(Environment.getNixDir(context), "nix/store")
        storeDir.mkdirs()
        assertTrue(Environment.isNixInitialized(context), "Nix should be initialized once store dir exists")
    }

    @Test
    fun `setupToolSymlinks maps proot to libproot`() {
        File(nativeLibDir, "libproot.so").writeText("elf")
        File(nativeLibDir, "libbash.so").writeText("elf")

        FirstRunSetup(context).setupToolSymlinks()

        val prootLink = File(filesDir, "usr/bin/proot")
        assertTrue(prootLink.exists(), "usr/bin/proot symlink must exist")
    }
}
