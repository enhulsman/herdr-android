package dev.herdr.android.ui.machines

import dev.herdr.android.ui.theme.Contrast
import dev.herdr.android.ui.theme.ContrastSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.HubSnapshot

// The "Higher text contrast" switch row under "This phone": off (like the Mac)
// it says so on its second line, on it has none; a tap switches it in place.
@RunWith(RobolectricTestRunner::class)
class TextContrastRowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val res = app.resources
    private val empty = HubSnapshot(emptyList(), emptyList(), emptyList(), null)

    @Test
    fun theRowNamesTheCurrentChoice() {
        assertEquals("Higher text contrast", res.getString(dev.herdr.android.R.string.machines_text_contrast))
        assertEquals("Off: like the Mac", machinesUi(empty, null, 0, res).textContrastDetail)
        assertEquals("Off: like the Mac", machinesUi(empty, null, 0, res, Contrast.Mac).textContrastDetail)
        assertEquals(null, machinesUi(empty, null, 0, res, Contrast.Aa).textContrastDetail)
    }

    @Test
    fun theSwitchIsOnForHigherContrast() {
        assertEquals(false, machinesUi(empty, null, 0, res).textContrastHigher)
        assertEquals(false, machinesUi(empty, null, 0, res, Contrast.Mac).textContrastHigher)
        assertEquals(true, machinesUi(empty, null, 0, res, Contrast.Aa).textContrastHigher)
    }

    @Test
    fun aTapSwitchesTheSettingBothWays() {
        val setting = ContrastSetting.open(app)
        val actions = withTextContrast(MachinesActions.Preview, setting)
        actions.onTextContrast()
        assertEquals(Contrast.Aa, setting.contrast.value)
        actions.onTextContrast()
        assertEquals(Contrast.Mac, setting.contrast.value)
    }
}
