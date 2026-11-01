package dev.herdr.android

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.herdr_mobile.coreVersion

// Loads the host build of libherdr_mobile through the generated uniffi bindings
// and JNA, the same path the app takes on a device. The build sets
// jna.library.path to the host library and herdr.version to the VERSION file.
class CoreBridgeTest {
    @Test
    fun coreVersionComesFromTheRustLibraryAndMatchesVersionFile() {
        assertEquals(System.getProperty("herdr.version"), coreVersion())
    }
}
