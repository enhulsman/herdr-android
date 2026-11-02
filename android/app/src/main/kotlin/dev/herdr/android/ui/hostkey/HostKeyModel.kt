package dev.herdr.android.ui.hostkey

import androidx.compose.runtime.Immutable
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// What the host-key views show, taken from the hub's snapshot (machine-connection
// spec, "Host keys are confirmed once and then enforced"). A prompt to trust a
// key exists only for a first connection (HostKeyUnconfirmed); a changed key
// (HostKeyMismatch) yields a HostKeyChange, which carries no way to trust it.

/** The first-connection confirm (render 8): Trust confirms exactly [fingerprint]. */
@Immutable
data class HostKeyPrompt(
    val machine: String,
    /** `host:port`, as the dialog names it. */
    val address: String,
    /** The key type as `ssh-keygen -l` names it ("ED25519"). */
    val algorithm: String,
    /** `SHA256:...` */
    val fingerprint: String,
)

/** A machine that presented a different key than the trusted one. */
@Immutable
data class HostKeyChange(
    val machine: String,
    val address: String,
    val trusted: String,
    val presented: String,
    /** The presented key's type; the trusted key's type is not kept. */
    val algorithm: String,
)

fun hostKeyPrompt(snapshot: HubSnapshot, machine: String): HostKeyPrompt? {
    val m = snapshot.machines.find { it.name == machine } ?: return null
    val state = m.state as? MachineState.HostKeyUnconfirmed ?: return null
    return HostKeyPrompt(m.name, address(m), state.algorithm, state.presented)
}

fun hostKeyChange(snapshot: HubSnapshot, machine: String?): HostKeyChange? {
    val m = snapshot.machines.find { it.name == machine } ?: return null
    val state = m.state as? MachineState.HostKeyMismatch ?: return null
    return HostKeyChange(m.name, address(m), state.confirmed, state.presented, state.algorithm)
}

private fun address(m: MachineView): String = machineAddress(m.host, m.port)

/** `host:port`, with an IPv6 address in brackets. */
fun machineAddress(host: String, port: UShort): String =
    if (':' in host) "[$host]:$port" else "$host:$port"
