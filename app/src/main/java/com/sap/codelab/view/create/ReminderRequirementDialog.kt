package com.sap.codelab.view.create

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.sap.codelab.R

internal class ReminderRequirementDialog : DialogFragment() {
    val requirement: ReminderRequirement?
        get() = readRequirement(requireArguments())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(args.getInt(TITLE_KEY))
            .setMessage(args.getString(MESSAGE_KEY))
            .setPositiveButton(args.getInt(POSITIVE_LABEL_KEY)) { _, _ -> finish(RationaleAction.CONTINUE) }
            .setNegativeButton(R.string.save_without_monitoring) { _, _ -> finish(RationaleAction.SKIP) }
        if (args.getBoolean(SETTINGS_OPTION_KEY)) {
            builder.setNeutralButton(R.string.open_app_settings) { _, _ -> finish(RationaleAction.OPEN_SETTINGS) }
        }
        return builder.create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        sendResult(RationaleAction.SKIP)
    }

    private fun finish(action: RationaleAction) {
        val manager = parentFragmentManager
        val result = resultFor(action)
        dismissNow()
        manager.setFragmentResult(RESULT_KEY, result)
    }

    private fun sendResult(action: RationaleAction) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, resultFor(action))
    }

    private fun resultFor(action: RationaleAction) = bundleOf(
        REQUIREMENT_KEY to requirement?.name,
        ACTION_KEY to action.name
    )

    companion object {
        const val TAG = "reminderRequirementDialog"
        const val RESULT_KEY = "reminderRequirementDialogResult"
        const val ACTION_KEY = "action"
        private const val REQUIREMENT_KEY = "requirement"
        private const val TITLE_KEY = "title"
        private const val MESSAGE_KEY = "message"
        private const val POSITIVE_LABEL_KEY = "positiveLabel"
        private const val SETTINGS_OPTION_KEY = "settingsOption"

        fun readRequirement(data: Bundle): ReminderRequirement? {
            val name = data.getString(REQUIREMENT_KEY)
            return ReminderRequirement.entries.firstOrNull { it.name == name }
        }

        fun newInstance(
            requirement: ReminderRequirement,
            @StringRes title: Int,
            message: String,
            @StringRes positiveLabel: Int = R.string.open_app_settings,
            showSettingsOption: Boolean = false
        ) = ReminderRequirementDialog().apply {
            arguments = bundleOf(
                REQUIREMENT_KEY to requirement.name,
                TITLE_KEY to title,
                MESSAGE_KEY to message,
                POSITIVE_LABEL_KEY to positiveLabel,
                SETTINGS_OPTION_KEY to showSettingsOption
            )
        }
    }
}
