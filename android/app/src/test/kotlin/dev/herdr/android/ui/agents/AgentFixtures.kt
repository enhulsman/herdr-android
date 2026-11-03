package dev.herdr.android.ui.agents

import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentRow
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView
import uniffi.herdr_mobile.OtherTerminal

// Invented machines, folders and agents for the Agents home tests (AGENTS.md:
// no real names). Rows are given in the hub's order, as Rust sorts them.

fun snapshot(
    machines: List<MachineView>,
    agents: List<AgentRow>,
    others: List<OtherTerminal> = emptyList(),
    notice: String? = null,
) = HubSnapshot(machines, agents, others, notice)

fun machine(name: String, state: MachineState, lastConnected: Long? = null) =
    MachineView(name, "$name.example.org", 22u, "dev", "SHA256:example", state, lastConnected?.toULong())

fun connected(name: String) = machine(name, MachineState.Connected("0.1.15"), lastConnected = 0L)

fun unreachable(name: String, lastConnected: Long) = machine(name, MachineState.Unreachable("connection refused"), lastConnected)

fun row(
    address: String,
    attention: AgentAttention,
    machine: String = "workstation",
    name: String = address,
    folder: String = "website",
    program: String? = "claude",
    label: String = attention.name,
    kind: String? = null,
    request: String? = null,
    progress: Int? = null,
    since: Long? = 0L,
) = AgentRow(
    machine = machine,
    address = address,
    name = name,
    attention = attention,
    label = label,
    progress = progress?.toUByte(),
    sinceMs = since?.toULong(),
    folder = folder,
    program = program,
    requestKind = kind ?: if (attention == AgentAttention.NEEDS_YOU) "Needs you" else if (attention == AgentAttention.ERROR) "Error" else null,
    request = request,
)

fun other(
    address: String,
    machine: String = "workstation",
    name: String = address,
    folder: String = "docs",
    program: String? = null,
) = OtherTerminal(machine, address, name, folder, program)
