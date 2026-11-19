package dev.kudde.android

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.kudde_mobile.coreVersion

// Loads the host build of libkudde_mobile through the generated uniffi bindings
// and JNA, the same path the app takes on a device. The build sets
// jna.library.path to the host library and kudde.version to the VERSION file.
class CoreBridgeTest {
    @Test
    fun coreVersionComesFromTheRustLibraryAndMatchesVersionFile() {
        assertEquals(System.getProperty("kudde.version"), coreVersion())
    }
}
