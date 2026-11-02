package dev.herdr.android.ui.addmachine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.herdr.android.ui.flows.MachineFlows
import dev.herdr.android.ui.hostkey.machineAddress
import uniffi.herdr_mobile.HubSnapshot

/**
 * The form fed by the flows: keeps what was typed (across rotation), shows the
 * checks from the first Save on and re-checks as the person types, and saves
 * only a form that passes.
 */
@Composable
fun AddMachineRoute(flows: MachineFlows, snapshot: HubSnapshot) {
    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(MachineInput().port) }
    var user by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(false) }
    val input = MachineInput(name, host, port, user)
    val taken = snapshot.machines.map { it.name }
    // While the hub connects, and while its host key waits in the dialog over the form.
    val waiting = (flows.waitingFor ?: flows.trusting)?.let { saved ->
        snapshot.machines.find { it.name == saved }?.let { machineAddress(it.host, it.port) } ?: input.host.trim()
    }
    AddMachineScreen(
        input = input,
        // After a save the machine is listed, so its own name would read as taken.
        errors = if (checked && waiting == null) validateMachine(input, taken) else null,
        waitingFor = waiting,
        error = flows.error.takeIf { flows.trusting == null },
        actions = AddMachineActions(
            onBack = { flows.back() },
            onChange = {
                name = it.name
                host = it.host
                port = it.port
                user = it.user
            },
            onSave = {
                checked = true
                validateMachine(input, taken).valid?.let(flows::save)
            },
        ),
    )
}
