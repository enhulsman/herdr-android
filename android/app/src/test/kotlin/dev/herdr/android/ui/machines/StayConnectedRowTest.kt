package dev.herdr.android.ui.machines

import dev.herdr.android.R
import dev.herdr.android.stay.StayConnectedRow
import dev.herdr.android.stay.StayConnectedSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.HubSnapshot

// stay-connected spec, "Stay connected is an option, off by default": a row
// that toggles in place, its second line "Off", "On · shows a notification" or
// "On · notifications are off" (permission refused or Status channel blocked).
@RunWith(RobolectricTestRunner::class)
class StayConnectedRowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val res = app.resources
    private val empty = HubSnapshot(emptyList(), emptyList(), emptyList(), null)

    @Test
    fun theRowNamesItsState() {
        assertEquals("Stay connected", res.getString(R.string.machines_stay_connected))
        assertEquals("Off", machinesUi(empty, null, 0, res).stayConnectedDetail)
        assertEquals("Off", machinesUi(empty, null, 0, res, stayConnected = StayConnectedRow.Off).stayConnectedDetail)
        assertEquals(
            "On · shows a notification",
            machinesUi(empty, null, 0, res, stayConnected = StayConnectedRow.On).stayConnectedDetail,
        )
        assertEquals(
            "On · notifications are off",
            machinesUi(empty, null, 0, res, stayConnected = StayConnectedRow.OnUnseen).stayConnectedDetail,
        )
    }

    @Test
    fun theRowMapsTheSettingAndNotificationState() {
        assertEquals(StayConnectedRow.Off, StayConnectedRow.of(on = false, notificationsShown = true))
        assertEquals(StayConnectedRow.Off, StayConnectedRow.of(on = false, notificationsShown = false))
        assertEquals(StayConnectedRow.On, StayConnectedRow.of(on = true, notificationsShown = true))
        assertEquals(StayConnectedRow.OnUnseen, StayConnectedRow.of(on = true, notificationsShown = false))
    }

    @Test
    fun aTapSwitchesTheSettingBothWays() {
        val setting = StayConnectedSetting.open(app)
        var turnedOn = 0
        val actions = withStayConnected(MachinesActions.Preview, setting, onTurnedOn = { turnedOn++ })
        actions.onStayConnected()
        assertTrue(setting.isOn())
        assertEquals(1, turnedOn)
        actions.onStayConnected()
        assertEquals(false, setting.isOn())
        assertEquals(1, turnedOn)
    }
}
