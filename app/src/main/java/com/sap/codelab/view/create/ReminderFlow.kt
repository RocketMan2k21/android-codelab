package com.sap.codelab.view.create

import android.os.Build
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase.ReminderRequest

internal enum class ReminderRequirement {
    PRECISE_LOCATION,
    BACKGROUND_LOCATION,
    LOCATION_SERVICES,
    NOTIFICATION_PERMISSION,
    APP_NOTIFICATIONS,
    CHANNEL_NOTIFICATIONS,
}

internal enum class ReminderSystemAction {
    REQUEST_FOREGROUND_LOCATION,
    REQUEST_BACKGROUND_LOCATION,
    OPEN_LOCATION_SETTINGS,
    OPEN_LOCATION_SERVICES,
    REQUEST_NOTIFICATIONS,
    OPEN_APP_NOTIFICATIONS,
    OPEN_CHANNEL_NOTIFICATIONS,
}

internal enum class RationaleAction { CONTINUE, OPEN_SETTINGS, SKIP }

internal sealed interface ReminderStep {
    data class ShowRationale(val requirement: ReminderRequirement) : ReminderStep

    data class AwaitSystemResult(
        val action: ReminderSystemAction,
        val launched: Boolean = false,
    ) : ReminderStep
}

internal sealed interface ReminderDecision {
    data class Wait(val step: ReminderStep) : ReminderDecision
    data class Save(val request: ReminderRequest) : ReminderDecision
}

/** Chooses the next reminder prerequisite from the current Android state and user choice. */
internal class ReminderFlow(private val sdkInt: Int) {

    /** Finds the first unmet requirement or proceeds with registration. */
    fun start(readiness: ReminderReadiness): ReminderDecision = when {
        !readiness.location.isPreciseLocationEnabled -> rationale(ReminderRequirement.PRECISE_LOCATION)
        sdkInt == Build.VERSION_CODES.Q && !readiness.location.isBackgroundLocationEnabled ->
            launch(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION)
        sdkInt >= Build.VERSION_CODES.R && !readiness.location.isBackgroundLocationEnabled ->
            rationale(ReminderRequirement.BACKGROUND_LOCATION)
        !readiness.locationServicesEnabled -> rationale(ReminderRequirement.LOCATION_SERVICES)
        else -> notificationRequirement(readiness.notificationAccess)?.let(::rationale)
            ?: ReminderDecision.Save(ReminderRequest.REGISTER)
    }

    /** Opens the screen for the requirement or continues without monitoring. */
    fun onRationaleResult(requirement: ReminderRequirement, action: RationaleAction): ReminderDecision {
        if (action == RationaleAction.SKIP) return ReminderDecision.Save(ReminderRequest.SKIP)

        return launch(when (requirement) {
            ReminderRequirement.PRECISE_LOCATION -> ReminderSystemAction.REQUEST_FOREGROUND_LOCATION
            ReminderRequirement.BACKGROUND_LOCATION -> ReminderSystemAction.OPEN_LOCATION_SETTINGS
            ReminderRequirement.LOCATION_SERVICES -> ReminderSystemAction.OPEN_LOCATION_SERVICES
            ReminderRequirement.NOTIFICATION_PERMISSION -> if (action == RationaleAction.OPEN_SETTINGS) {
                ReminderSystemAction.OPEN_APP_NOTIFICATIONS
            } else {
                ReminderSystemAction.REQUEST_NOTIFICATIONS
            }
            ReminderRequirement.APP_NOTIFICATIONS -> ReminderSystemAction.OPEN_APP_NOTIFICATIONS
            ReminderRequirement.CHANNEL_NOTIFICATIONS -> ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS
        })
    }

    /** Checks the current grants and settings after returning from a system screen. */
    fun onSystemResult(action: ReminderSystemAction, readiness: ReminderReadiness): ReminderDecision {
        val location = readiness.location
        val runtimeRequestDeclined = when (action) {
            ReminderSystemAction.REQUEST_FOREGROUND_LOCATION -> !location.isPreciseLocationEnabled
            ReminderSystemAction.REQUEST_BACKGROUND_LOCATION -> !location.isBackgroundLocationEnabled
            ReminderSystemAction.REQUEST_NOTIFICATIONS ->
                readiness.notificationAccess == NotificationAccess.PERMISSION_REQUIRED
            else -> false
        }
        if (runtimeRequestDeclined) return ReminderDecision.Save(ReminderRequest.SKIP)

        val incompleteSettings = when (action) {
            ReminderSystemAction.OPEN_LOCATION_SETTINGS -> ReminderRequirement.BACKGROUND_LOCATION.takeIf {
                !location.isPreciseLocationEnabled || !location.isBackgroundLocationEnabled
            }
            ReminderSystemAction.OPEN_LOCATION_SERVICES ->
                ReminderRequirement.LOCATION_SERVICES.takeUnless { readiness.locationServicesEnabled }
            ReminderSystemAction.OPEN_APP_NOTIFICATIONS, ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS ->
                notificationRequirement(readiness.notificationAccess)
            else -> null
        }
        return incompleteSettings?.let(::rationale) ?: start(readiness)
    }

    private fun notificationRequirement(access: NotificationAccess): ReminderRequirement? = when (access) {
        NotificationAccess.ENABLED -> null
        NotificationAccess.PERMISSION_REQUIRED -> ReminderRequirement.NOTIFICATION_PERMISSION
        NotificationAccess.APP_DISABLED -> ReminderRequirement.APP_NOTIFICATIONS
        NotificationAccess.CHANNEL_DISABLED -> ReminderRequirement.CHANNEL_NOTIFICATIONS
    }

    private fun rationale(requirement: ReminderRequirement) =
        ReminderDecision.Wait(ReminderStep.ShowRationale(requirement))

    private fun launch(action: ReminderSystemAction) =
        ReminderDecision.Wait(ReminderStep.AwaitSystemResult(action))
}
