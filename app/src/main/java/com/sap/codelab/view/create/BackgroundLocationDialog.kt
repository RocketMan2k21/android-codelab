package com.sap.codelab.view.create

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.sap.codelab.R

internal class BackgroundLocationDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(requireContext())
        .setTitle(R.string.background_location_settings_title)
        .setMessage(requireArguments().getString(MESSAGE_KEY))
        .setPositiveButton(R.string.open_app_settings) { _, _ -> sendResult(openSettings = true) }
        .setNegativeButton(R.string.save_without_monitoring) { _, _ -> sendResult(openSettings = false) }
        .create()

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        sendResult(openSettings = false)
    }

    private fun sendResult(openSettings: Boolean) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(OPEN_SETTINGS_KEY to openSettings))
    }

    companion object {
        const val TAG = "backgroundLocationDialog"
        const val RESULT_KEY = "backgroundLocationDialogResult"
        const val OPEN_SETTINGS_KEY = "openSettings"
        private const val MESSAGE_KEY = "message"

        fun newInstance(message: String) = BackgroundLocationDialog().apply {
            arguments = bundleOf(MESSAGE_KEY to message)
        }
    }
}
