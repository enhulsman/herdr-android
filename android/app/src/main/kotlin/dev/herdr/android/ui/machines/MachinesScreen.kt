package dev.herdr.android.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.components.DialogButton
import dev.herdr.android.ui.components.DialogButtons
import dev.herdr.android.ui.components.DialogText
import dev.herdr.android.ui.components.ModalDialog
import dev.herdr.android.ui.components.OutlineButton
import dev.herdr.android.ui.components.SubScreenTopBar
import dev.herdr.android.ui.components.Toggle
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

// The Machines screen, render 7 (design/checkpoints/7-machines.png; sizes from
// .mcard, .mhead, .mrow, .caption in design/renders/screens.html). Pure: it
// lays out a MachinesUi and calls MachinesActions; MachinesRoute feeds it from
// the hub.
// Render 7's Alerts, Mute alerts and Send test alert rows follow the head
// (and a host-key or phone-key link, which is the more urgent).

/**
 * What the screen can ask for. [onUnlock] and [onTryAgain] go to the hub
 * (hubActions); the host-key, phone-key, Add machine and Remove seams go to
 * MachineFlows; [onExport] and [onImport] open the system file picker
 * (MachinesRoute).
 */
data class MachinesActions(
    val onBack: (() -> Unit)?,
    val onUnlock: () -> Unit,
    val onTryAgain: () -> Unit,
    /** Host key to confirm or compare, by machine name. */
    val onHostKey: (String) -> Unit,
    /** The authorized_keys line with its copy button, for the machine that refused the key. */
    val onAddPhoneKey: (String) -> Unit,
    /** The same line from the "Phone key" row, for any machine. */
    val onPhoneKey: () -> Unit,
    val onAddMachine: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    /** The "Text contrast" row: switches to the other choice in place. */
    val onTextContrast: () -> Unit = {},
    /** The "Stay connected" row: turns the option on or off in place (MachinesRoute). */
    val onStayConnected: () -> Unit = {},
    /** The card's "Remove machine", by machine name: opens the confirm. */
    val onRemove: (String) -> Unit = {},
    /** The confirm's Remove. */
    val onConfirmRemove: () -> Unit = {},
    /** The confirm's Cancel, or a tap on the scrim. */
    val onCancelRemove: () -> Unit = {},
    /** "Network permission is off": this app's page in Android settings (MachinesRoute). */
    val onOpenSettings: () -> Unit = {},
    /** The Alerts row's button: turn alerts on, try again, or allow notifications (MachinesRoute). */
    val onAlerts: (AlertsButton) -> Unit = {},
    /** "Mute alerts", by machine name, to the new state. */
    val onMute: (String, Boolean) -> Unit = { _, _ -> },
    /** "Send test alert"'s Send, by machine name. */
    val onSendTest: (String) -> Unit = {},
) {
    companion object {
        /** Does nothing; for screenshots and flows not built yet. */
        val Preview = MachinesActions(null, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

// Render 7's sizes without a token of their own: the card row's second line
// and the "This phone" caption (12.5 sp).
private val rowDetail = HerdrType.secondary.copy(fontSize = 12.5.sp)
private val caption = HerdrType.sectionHeader.copy(fontSize = 12.5.sp)

@Composable
fun MachinesScreen(ui: MachinesUi, actions: MachinesActions, modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    Box(modifier.fillMaxSize().background(c.chrome)) {
        MachinesList(ui, actions)
        ui.removing?.let { name ->
            ModalDialog(stringResource(R.string.remove_title, name), onDismiss = actions.onCancelRemove) {
                DialogText(stringResource(R.string.remove_body))
                DialogButtons {
                    DialogButton(stringResource(R.string.cancel), actions.onCancelRemove)
                    DialogButton(stringResource(R.string.remove_confirm), actions.onConfirmRemove, solid = true)
                }
            }
        }
    }
}

@Composable
private fun MachinesList(ui: MachinesUi, actions: MachinesActions) {
    val c = HerdrTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubScreenTopBar(stringResource(R.string.machines_title), stringResource(R.string.back), actions.onBack)
        ui.notice?.let { Notice(it) }
        ui.error?.let { Notice(it, error = true, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (ui.machines.isEmpty()) {
            BasicText(
                stringResource(R.string.machines_empty),
                Modifier.padding(start = 20.dp, end = 20.dp, top = HerdrSpacing.s8, bottom = HerdrSpacing.s20),
                style = HerdrType.body.copy(color = c.muted),
            )
        }
        for (machine in ui.machines) {
            MachineCard(machine, actions)
        }
        OutlineButton(
            stringResource(R.string.machines_add),
            actions.onAddMachine,
            Modifier
                .padding(start = HerdrLayout.screenPaddingX, end = HerdrLayout.screenPaddingX, top = HerdrSpacing.s2)
                .fillMaxWidth(),
        )
        BasicText(
            stringResource(R.string.machines_this_phone),
            Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = HerdrSpacing.s8),
            style = caption.copy(color = c.dimText),
        )
        Card {
            CardRow(
                stringResource(R.string.machines_phone_key),
                ui.phoneKeyDetail,
                first = true,
                // Render 7 draws this row without a chevron; the owner asked for one (it opens the line).
                onClick = actions.onPhoneKey,
            )
            // Switch rows (owner, 2026-11-13; render 7 drew them as plain rows): the whole row toggles, as Mute alerts does.
            CardRow(
                stringResource(R.string.machines_text_contrast),
                ui.textContrastDetail,
                chevron = false,
                toggled = ui.textContrastHigher,
                trailing = { Toggle(ui.textContrastHigher) },
                onClick = actions.onTextContrast,
            )
            CardRow(
                stringResource(R.string.machines_stay_connected),
                ui.stayConnectedDetail,
                chevron = false,
                toggled = ui.stayConnectedOn,
                trailing = { Toggle(ui.stayConnectedOn) },
                onClick = actions.onStayConnected,
            )
            // Shown even with no machines (the spec makes export unconditional), but dim and inert until one exists.
            CardRow(
                stringResource(R.string.machines_export),
                ui.exportDetail,
                onClick = actions.onExport.takeIf { ui.canExport },
                enabled = ui.canExport,
            )
            // Always shown: it matters most on a fresh install's empty list.
            CardRow(
                stringResource(R.string.machines_import),
                stringResource(R.string.machines_import_detail),
                onClick = actions.onImport,
            )
        }
        // Announced by screen readers: it answers the picker the person just closed.
        ui.transfer?.let { Notice(it.text, error = it.isError, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Spacer(Modifier.height(HerdrSpacing.s24))
    }
}

/** A line on the hover background; [error] draws the text in the error colour (error = blocked). */
@Composable
private fun Notice(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        modifier
            .padding(start = HerdrLayout.screenPaddingX, end = HerdrLayout.screenPaddingX, bottom = 10.dp)
            .fillMaxWidth()
            .background(c.hover, RoundedCornerShape(HerdrRadius.notice))
            .padding(horizontal = HerdrSpacing.s12, vertical = 9.dp),
        style = HerdrType.secondary.copy(color = if (error) c.blocked else c.muted),
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.machineCard)
    Column(
        Modifier
            .padding(start = HerdrLayout.screenPaddingX, end = HerdrLayout.screenPaddingX, bottom = HerdrSpacing.s14)
            .fillMaxWidth()
            .clip(shape)
            .background(c.popover)
            .border(HerdrBorder.thin, c.border, shape)
            // A CSS border takes room; a Compose border is drawn over the content.
            .padding(HerdrBorder.thin),
        content = content,
    )
}

@Composable
private fun MachineCard(machine: MachineCardUi, actions: MachinesActions) {
    val c = HerdrTheme.colors
    Card {
        Row(
            Modifier.padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, top = HerdrSpacing.s14, bottom = HerdrSpacing.s12),
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.align(Alignment.Top).padding(top = 9.dp)) { Mark(machine.mark) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(machine.name, style = HerdrType.machineName.copy(color = c.fg))
                StatusLine(machine)
                machine.detail?.let {
                    BasicText(it, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 3)
                }
            }
            when (machine.button) {
                CardButton.Unlock -> OutlineButton(stringResource(R.string.action_unlock), actions.onUnlock, compact = true)
                CardButton.TryAgain -> OutlineButton(stringResource(R.string.action_try_again), actions.onTryAgain, compact = true)
                CardButton.OpenSettings -> OutlineButton(stringResource(R.string.action_open_settings), actions.onOpenSettings, compact = true)
                null -> Unit
            }
        }
        machine.link?.let { link ->
            CardRow(link.label, link.detail) {
                when (link.kind) {
                    CardLink.CheckHostKey, CardLink.CompareHostKeys -> actions.onHostKey(machine.name)
                    CardLink.AddPhoneKey -> actions.onAddPhoneKey(machine.name)
                }
            }
        }
        machine.alerts?.let { row ->
            CardRow(stringResource(R.string.alerts_row), row.detail, chevron = false, trailing = {
                row.button?.let { button ->
                    val label = when (button) {
                        AlertsButton.TurnOn -> R.string.alerts_turn_on
                        AlertsButton.TryAgain -> R.string.action_try_again
                        AlertsButton.Allow -> R.string.alerts_allow
                    }
                    OutlineButton(stringResource(label), { actions.onAlerts(button) }, compact = true)
                }
            })
        }
        machine.mute?.let { muted ->
            CardRow(
                stringResource(R.string.alerts_mute),
                stringResource(R.string.alerts_mute_detail),
                chevron = false,
                toggled = muted,
                trailing = { Toggle(muted) },
                onClick = { actions.onMute(machine.name, !muted) },
            )
        }
        machine.test?.let { test ->
            CardRow(stringResource(R.string.alerts_test), test.detail, chevron = false, trailing = {
                OutlineButton(stringResource(R.string.alerts_test_send), { actions.onSendTest(machine.name) }, compact = true)
            })
        }
        // Last on the card; opens a confirm, so no chevron.
        CardRow(stringResource(R.string.machines_remove), null, chevron = false) { actions.onRemove(machine.name) }
    }
}

@Composable
private fun Mark(mark: StatusMark) {
    val c = HerdrTheme.colors
    val dot = Modifier.size(9.dp).clip(CircleShape)
    when (mark) {
        StatusMark.Connected -> Box(dot.background(c.connected))
        StatusMark.Ring -> Box(dot.border(1.5.dp, c.idle, CircleShape))
    }
}

@Composable
private fun StatusLine(machine: MachineCardUi) {
    val c = HerdrTheme.colors
    val text = buildAnnotatedString {
        machine.status.forEachIndexed { i, segment ->
            if (i > 0) append(machine.separator)
            val colour = when (segment.tone) {
                Tone.Muted -> c.muted
                Tone.Warning -> c.working
            }
            withStyle(SpanStyle(color = colour)) { append(segment.text) }
        }
    }
    BasicText(text, style = HerdrType.secondary.copy(color = c.muted))
}

/**
 * A row in a card: label over a dim second line, a chevron when it leads
 * somewhere unless [chevron] says otherwise. A row not [enabled] draws its
 * label in the dim text colour too, and screen readers hear it as disabled.
 */
@Composable
private fun CardRow(
    label: String,
    detail: String?,
    first: Boolean = false,
    /** Null: a chevron exactly when [onClick] is set. */
    chevron: Boolean? = null,
    enabled: Boolean = true,
    /** A switch row: the whole row toggles and screen readers hear its state. */
    toggled: Boolean? = null,
    /** At the row's end: a button or a toggle. */
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = HerdrTheme.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(HerdrBorder.thin).background(c.border))
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    when {
                        onClick == null -> Modifier
                        toggled != null -> Modifier
                            .clickable(role = Role.Switch, onClick = onClick)
                            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(toggled) }
                        else -> Modifier.clickable(role = Role.Button, onClick = onClick)
                    },
                )
                .then(if (enabled) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() })
                .padding(horizontal = HerdrSpacing.s16, vertical = HerdrSpacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s2)) {
                BasicText(label, style = HerdrType.settingRow.copy(color = if (enabled) c.fg else c.dimText))
                detail?.let { BasicText(it, style = rowDetail.copy(color = c.dimText)) }
            }
            trailing?.invoke()
            if (chevron ?: (onClick != null)) ChevronIcon(Chevron.Right, 14.dp, c.fg)
        }
    }
}
