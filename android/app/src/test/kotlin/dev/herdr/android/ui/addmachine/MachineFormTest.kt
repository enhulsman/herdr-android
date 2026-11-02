package dev.herdr.android.ui.addmachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Add machine form's checks (machine-connection spec, "Machines are an
// app-local list": name, host, port, user). They mirror the hub's own
// validate() in crates/herdr-mobile/src/machines.rs, so a form that passes here
// is not refused there, and they add what only the app knows: names in use.
class MachineFormTest {
    private val ok = MachineInput(name = "workstation", host = "workstation.example.org", port = "22", user = "dev")

    private fun errors(input: MachineInput, taken: Collection<String> = listOf("server")) =
        validateMachine(input, taken)

    @Test
    fun aCompleteFormIsValidAndKeepsWhatWasTyped() {
        val result = errors(ok)
        assertTrue(result.ok)
        assertEquals(ValidMachine("workstation", "workstation.example.org", 22u, "dev"), result.valid)
    }

    @Test
    fun aNewFormStartsWithPort22() {
        assertEquals("22", MachineInput().port)
        assertEquals("", MachineInput().name)
    }

    @Test
    fun spacesAroundWhatWasTypedAreDropped() {
        val result = errors(MachineInput(" workstation ", " 192.0.2.10 ", " 2200 ", " dev "))
        assertEquals(ValidMachine("workstation", "192.0.2.10", 2200u, "dev"), result.valid)
    }

    @Test
    fun theNameIsRequired() {
        assertEquals(FieldError.NameRequired, errors(ok.copy(name = "")).name)
        assertEquals(FieldError.NameRequired, errors(ok.copy(name = "   ")).name)
    }

    @Test
    fun theNameMustNotBeInUse() {
        val result = errors(ok.copy(name = "server"))
        assertEquals(FieldError.NameTaken, result.name)
        assertNull(result.valid)
        // As the hub compares them: exactly, after trimming.
        assertEquals(FieldError.NameTaken, errors(ok.copy(name = " server ")).name)
        assertNull(errors(ok.copy(name = "Server")).name)
    }

    @Test
    fun theNameFitsOnOneLineIn64Characters() {
        assertNull(errors(ok.copy(name = "x".repeat(64))).name)
        assertEquals(FieldError.NameTooLong, errors(ok.copy(name = "x".repeat(65))).name)
        assertEquals(FieldError.NameOneLine, errors(ok.copy(name = "two\nlines")).name)
    }

    @Test
    fun theHostIsRequiredWithoutSpacesOrALeadingDash() {
        assertEquals(FieldError.HostRequired, errors(ok.copy(host = "")).host)
        assertEquals(FieldError.HostInvalid, errors(ok.copy(host = "has space")).host)
        // An ssh option smuggled in as a host.
        assertEquals(FieldError.HostInvalid, errors(ok.copy(host = "-oProxyCommand=x")).host)
        assertNull(errors(ok.copy(host = "192.0.2.10")).host)
        assertNull(errors(ok.copy(host = "2001:db8::10")).host)
    }

    @Test
    fun thePortIsANumberFrom1To65535() {
        for (bad in listOf("", "0", "65536", "-1", "22a", "99999999999")) {
            assertEquals(bad, FieldError.PortInvalid, errors(ok.copy(port = bad)).port)
        }
        for (good in listOf("1", "22", "65535")) {
            assertNull(good, errors(ok.copy(port = good)).port)
        }
    }

    @Test
    fun theUserIsALoginNameWithoutSpacesOrAt() {
        assertEquals(FieldError.UserRequired, errors(ok.copy(user = "")).user)
        assertEquals(FieldError.UserInvalid, errors(ok.copy(user = "dev@host")).user)
        assertEquals(FieldError.UserInvalid, errors(ok.copy(user = "a b")).user)
        assertEquals(FieldError.UserInvalid, errors(ok.copy(user = "-l")).user)
    }

    @Test
    fun everyFieldIsCheckedAtOnce() {
        val result = errors(MachineInput(name = "", host = "", port = "0", user = ""))
        assertEquals(FieldError.NameRequired, result.name)
        assertEquals(FieldError.HostRequired, result.host)
        assertEquals(FieldError.PortInvalid, result.port)
        assertEquals(FieldError.UserRequired, result.user)
        assertNull(result.valid)
    }
}
