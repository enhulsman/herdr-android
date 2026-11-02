package dev.herdr.android.ui.addmachine

import androidx.compose.runtime.Immutable

// What the Add machine form checks before it asks the hub. The rules mirror
// validate() in crates/herdr-mobile/src/machines.rs, so the hub's own refusal
// stays a backstop the person should not see; MachineFormTest pins them.

/** What the person typed, as typed. */
@Immutable
data class MachineInput(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
)

/** The form after its checks, ready for the hub. */
data class ValidMachine(val name: String, val host: String, val port: UShort, val user: String)

enum class FieldError {
    NameRequired,
    NameTaken,
    NameTooLong,
    NameOneLine,
    HostRequired,
    HostInvalid,
    PortInvalid,
    UserRequired,
    UserInvalid,
}

@Immutable
data class FormErrors(
    val name: FieldError? = null,
    val host: FieldError? = null,
    val port: FieldError? = null,
    val user: FieldError? = null,
    /** Set when every field passed. */
    val valid: ValidMachine? = null,
) {
    val ok: Boolean get() = valid != null
}

// The hub's limits (machines.rs NAME_MAX, HOST_MAX, USER_MAX).
private const val NAME_MAX = 64
private const val HOST_MAX = 253
private const val USER_MAX = 64

/** Checks every field at once; [taken] are the names already listed. */
fun validateMachine(input: MachineInput, taken: Collection<String>): FormErrors {
    val name = input.name.trim()
    val host = input.host.trim()
    val user = input.user.trim()
    val port = input.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    val nameError = when {
        name.isEmpty() -> FieldError.NameRequired
        name.any { it.isISOControl() } -> FieldError.NameOneLine
        name.codePointCount(0, name.length) > NAME_MAX -> FieldError.NameTooLong
        name in taken -> FieldError.NameTaken
        else -> null
    }
    val hostError = when {
        host.isEmpty() -> FieldError.HostRequired
        host.length > HOST_MAX || host.startsWith('-') || host.any { it.isWhitespace() || it.isISOControl() } ->
            FieldError.HostInvalid
        else -> null
    }
    val portError = if (port == null) FieldError.PortInvalid else null
    val userError = when {
        user.isEmpty() -> FieldError.UserRequired
        user.length > USER_MAX || user.startsWith('-') ||
            user.any { it.isWhitespace() || it.isISOControl() || it == '@' } -> FieldError.UserInvalid
        else -> null
    }
    val valid = if (nameError == null && hostError == null && portError == null && userError == null) {
        ValidMachine(name, host, port!!.toUShort(), user)
    } else {
        null
    }
    return FormErrors(nameError, hostError, portError, userError, valid)
}
