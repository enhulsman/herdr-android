package dev.herdr.android.ui.transfer

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.herdr.android.R

/** The inline notice under "This phone" after an export or import; [isError] draws it in the error colour. */
@Immutable
data class TransferNoticeUi(val text: String, val isError: Boolean)

/** [result] in words (TransferNoticeTest pins them). */
fun transferNotice(result: TransferResult, res: Resources): TransferNoticeUi {
    fun ok(text: String) = TransferNoticeUi(text, isError = false)
    fun error(text: String) = TransferNoticeUi(text, isError = true)
    // Reasons come from the hub or a storage app; the sentence adds its own full stop.
    fun reason(text: String) = text.trim().trimEnd('.')
    return when (result) {
        TransferResult.Exported -> ok(res.getString(R.string.transfer_exported))
        is TransferResult.ExportFailed -> error(
            result.reason?.takeIf { it.isNotBlank() }
                ?.let { res.getString(R.string.transfer_export_failed_because, reason(it)) }
                ?: res.getString(R.string.transfer_export_failed),
        )
        is TransferResult.Imported -> ok(
            if (result.added == 0) {
                res.getString(R.string.transfer_imported_none)
            } else {
                res.getQuantityString(R.plurals.transfer_imported, result.added, result.added)
            },
        )
        TransferResult.FileTooLarge -> error(res.getString(R.string.transfer_too_large))
        TransferResult.FileEmpty -> error(res.getString(R.string.transfer_empty))
        TransferResult.NotAList -> error(res.getString(R.string.transfer_not_a_list))
        is TransferResult.ImportFailed -> error(res.getString(R.string.transfer_import_failed, reason(result.reason)))
        is TransferResult.ReadFailed -> error(
            result.reason?.takeIf { it.isNotBlank() }
                ?.let { res.getString(R.string.transfer_read_failed_because, reason(it)) }
                ?: res.getString(R.string.transfer_read_failed),
        )
        TransferResult.NoPicker -> error(res.getString(R.string.transfer_no_picker))
    }
}
