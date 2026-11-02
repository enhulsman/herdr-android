package dev.herdr.android.ui.machines

import dev.herdr.android.ui.theme.Contrast
import dev.herdr.android.ui.theme.ContrastSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.HubSnapshot

// The "Text contrast" row under "This phone": its second line names the current
// choice, and a tap switches to the other one in place (no dialog).
@RunWith(RobolectricTestRunner::class)
class TextContrastRowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val res = app.resources
    private val empty = HubSnapshot(emptyList(), emptyList(), emptyList(), null)

    @Test
    fun theRowNamesTheCurrentChoice() {
        assertEquals("Text contrast", res.getString(dev.herdr.android.R.string.machines_text_contrast))
        assertEquals("Like the Mac", machinesUi(empty, null, 0, res).textContrastDetail)
        assertEquals("Like the Mac", machinesUi(empty, null, 0, res, Contrast.Mac).textContrastDetail)
        assertEquals("Higher", machinesUi(empty, null, 0, res, Contrast.Aa).textContrastDetail)
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
