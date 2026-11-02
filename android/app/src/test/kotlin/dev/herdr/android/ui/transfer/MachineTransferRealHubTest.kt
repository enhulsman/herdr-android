package dev.herdr.android.ui.transfer

import dev.herdr.android.hub.HubHost
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.herdr_mobile.Hub
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.SignException
import uniffi.herdr_mobile.SshSigner
import uniffi.herdr_mobile.UniffiLib
import uniffi.herdr_mobile.uniffiCallbackInterfaceHubListener
import uniffi.herdr_mobile.uniffiCallbackInterfaceSshSigner

// "Restoring the list on a new install", end to end through the real hub (the
// host build of the Rust library): an export from one install, imported into
// a fresh one, lists the machines with no host key, so each asks for setup.
// The file also carries a host key that the import must ignore.
class MachineTransferRealHubTest {
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<HubHost>()

    private class MemoryStore : DocumentStore {
        val files = mutableMapOf<String, ByteArray>()
        override fun write(document: String, text: String) {
            files[document] = text.toByteArray()
        }
        override fun read(document: String, limit: Int): ByteArray = files.getValue(document)
    }

    @Before
    fun registerBindings() {
        // See HubHostTest: this class loader's callback tables, not Robolectric's.
        uniffiCallbackInterfaceHubListener.register(UniffiLib)
        uniffiCallbackInterfaceSshSigner.register(UniffiLib)
    }

    @After
    fun cleanUp() {
        hosts.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun install(): HubHost {
        val dir = Files.createTempDirectory("hub").toFile().also { dirs += it }
        val spki = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded
        val signer = object : SshSigner {
            override fun sign(data: ByteArray): ByteArray = throw SignException.NeedsUserAuth()
        }
        return HubHost(createHub = { l -> Hub(dir.path, spki, signer, l) }, mainExecutor = Executor { it.run() })
            .also { hosts += it; it.start() }
    }

    private fun HubHost.await(done: (HubSnapshot) -> Boolean): HubSnapshot {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!done(snapshot.value) && System.nanoTime() < deadline) Thread.onSpinWait()
        return snapshot.value
    }

    @Test
    fun anExportRestoresIntoAFreshInstallWithoutHostKeys() = runBlocking {
        val store = MemoryStore()
        val old = install()
        old.hub!!.addMachine("workstation", "workstation.example.org", 22u, "dev")
        old.hub!!.addMachine("server", "192.0.2.10", 2200u, "dev")
        MachineTransfer(old, store, CoroutineScope(Dispatchers.Unconfined)).export("content://docs/herdr-machines.json")
        val exported = String(store.files.getValue("content://docs/herdr-machines.json"))
        assertFalse(exported, exported.contains("SHA256"))

        // Someone edits a host key into the file: the import still trusts nothing.
        store.files["content://docs/edited.json"] = exported
            .replace("\"user\": \"dev\"", "\"user\": \"dev\", \"host_key\": \"SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p\"")
            .toByteArray()

        val fresh = install()
        val transfer = MachineTransfer(fresh, store, CoroutineScope(Dispatchers.Unconfined))
        transfer.import("content://docs/edited.json")
        assertEquals(TransferResult.Imported(2), transfer.result.value)

        val machines = fresh.await { it.machines.size == 2 }.machines
        assertEquals(setOf("workstation", "server"), machines.map { it.name }.toSet())
        for (m in machines) {
            assertNull("${m.name} arrived with a host key", m.hostKey)
            // The hub starts suspended: nothing dials, nothing is trusted.
            assertEquals(MachineState.Paused, m.state)
        }

        // Importing the same file again adds nothing and keeps the list.
        transfer.import("content://docs/edited.json")
        assertEquals(TransferResult.Imported(0), transfer.result.value)
        assertTrue(fresh.snapshot.value.machines.size == 2)
    }
}
