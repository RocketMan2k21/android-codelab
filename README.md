# android-codelab (UPDATED, PLEASE READ)
Android project that serves as a base for code challenges implemented by applicants.
The base is written in kotlin. 

(!)If you have the NDK plugin installed, please disable it for the project, as errors may occur.

# Android Coding Challenges
Coding challenges are useful when the applicant does not provide a github repository or any work samples. Even if a github repository has been provided it is generally a good idea to give the applicant a task to solve and have him present his solution in a separate session. 

## General Instructions
The following instructions/conditions are valid independently of the actual coding challenge

- The code base has been tested with Android Studio Narwhal Feature Drop which is the recommended version, however feel free to try a higher version and adjust the configuration as needed
- The task should be implemented in kotlin
- Approach this task as if it was a real-world implementation - i.e. exactly how you would approach the task if you were working for a company
- 3rd party libraries may be used
- 3rd party libraries must be wrapped: They should be abstracted out, so any other library could be plugged into the solution
- The base project for this task will be provided by us
- Once completed, please send us your solution and presents it to us, followed by a discussion about the implementation and design decisions made
- The solution can be sent as a zip file or as a publicly accessible github/gitlub etc project link
- The solution sent to us must be complete, i.e. can be opened directly via Android Studio without additional configuration

## Location Based Notifications
In this challenge the applicant has to implement location-based notifications/reminders, the following conditions are given:

- When creating a new memo, the user provides a location by selecting a point on a map (for instance: google maps or open street maps)
- The memo is then saved
- Once the user physically reaches that location, a notification should be displayed in the phone's status bar, that contains the title and the first 140 characters of the note text
- "Reaching the location" is defined as follows: The user is within 200 meters of the location he initially selected during the memo creation
- The notification should also contain an icon (the icon choice is up to you)
- Some form of location tracking will be required to achieve the desired result, i.e. to know when a user is close to the given location of a memo
- The feature must also work, when the app is running in the background (or possibly not running at all)

## Android SDK target

The app targets Android 13 (API 33) to control the runtime notification permission flow on Android 13 and later. It supports Android 8.1 (API 27) and later.

## Architecture and dependency injection

The initial version provided Room-backed memo creation, listing, and viewing. The reminder feature adds map selection, permission checks, and geofence delivery to that flow. Activities render XML layouts with View Binding and launch Android permission and settings screens. `App` creates the shared Room repository, reminder services, and use cases lazily. ViewModel factories pass dependencies through constructors.

```text
Activities → ViewModels → Use cases ─┬→ MemoRepository → Room
                                     └→ GeofenceManager → Play services

Play services → GeofenceBroadcastReceiver → GeofenceNotificationManager
                                            └─ notification tap → ViewMemo
```

### ViewModel changes

- `CreateMemoViewModel` backs up the `MemoDraft`, including the selected point, in `SavedStateHandle`. It exposes save and prerequisite steps as a `StateFlow` and delegates the write to `CreateMemoUseCase`.
- `HomeViewModel` exposes the memo list and the monitoring banner state. It refreshes both when Home resumes and delegates pending registrations and completion changes to `MonitoringUseCase`.
- `ViewMemoViewModel` loads the memo by ID, tracks completion progress, and delegates **Mark done** to the same use case. Completing a memo from either screen saves its completion state and removes its geofence if it has a location.

### Location and permissions

- `MapActivity` returns the map’s center point when the user confirms. `FusedLocationDataSource` wraps a one-time Play services location lookup to center the map with either precise or approximate foreground access.
- `LocationPermissionSource` reads location grants and the device-wide Location switch. `LocationPermissionLauncher` wraps foreground and background permission requests, and `NotificationPermissionRequestHistory` records whether the app has requested notification permission so the UI can choose between a prompt and Settings.
- `ReminderFlow` checks precise foreground location, background access, device Location, then notification access. Android 10 requests background access at runtime, Android 11 and later use app settings, and earlier versions need only the foreground grant. `ReminderRequirementDialog` explains missing requirements and lets the user save without a reminder. Home offers **Fix** actions for pending reminders and uses `PlayServicesLocationSettingsChecker` to check whether Play services can resolve the device’s location settings.

### Geofence reminder flow

1. `CreateMemoUseCase` saves the memo to Room first. If the user skips reminder setup or registration fails, the memo stays saved. `MonitoringUseCase` can register pending reminders for open memos once the requirements are met. Both record successful registrations through `RoomMemoRepository`.
2. `PlayServicesGeofenceManager` implements `GeofenceManager` and registers a 200 m entry geofence keyed by the saved memo ID. Play services delivers matching transitions to a manifest-registered `GeofenceBroadcastReceiver`, including a possible initial entry if the device is already inside the zone.
3. The receiver uses the IDs to look up open memos in Room. `GeofenceNotificationManager` checks the Android 13+ runtime grant and the app and channel settings before posting to the **Location reminders** channel. Each notification includes the memo title, the first 140 characters of its text, and an icon. Tapping it opens the matching memo in `ViewMemo`.

## Running tests

Run these commands from the repository root with JDK 21 and Android SDK 36 configured. On Windows, use `gradlew.bat` in place of `./gradlew`.

**Local unit tests** cover memo creation and monitoring. They run on the JVM without an emulator or device.

```sh
./gradlew :app:testDebugUnitTest
```

**Instrumented tests** cover the memo permission flow, activity recreation, and Room persistence. Start an Android emulator or connect a device running Android 8.1 (API 27) or later with USB debugging enabled.

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Limitations & Future Improvements

Geofence events are asynchronous. Crossing the 200 m boundary does not guarantee an immediate notification, especially in the background.

- **Geofence limit.** With all 100 geofence slots in use, an additional memo is saved in Room and its registration fails. **Future.** Keep excess reminders pending, prioritize active slots by memo priority, and promote pending reminders as slots free up.
- **Permission changes.** Home rechecks precise and background location access when it resumes. Revoking access can leave the registration flags stored in Room stale. If access is missing during creation, the user can still save the memo without a reminder. **Future.** Mark affected reminders as suspended and reconcile registrations after access is restored.
- **Nearby geofences.** Each memo has its own geofence, so identical or overlapping locations can produce multiple notifications. **Future.** Share a geofence for identical points and group notifications for overlapping points while preserving each memo's trigger area.
- **Device reboot.** Memos and their registration flags persist in Room, while geofence registrations are lost. **Future.** On `BOOT_COMPLETED`, reload eligible open memos, register them again, and update the stored flags from the results.
- **Location settings.** The app checks the device-wide Location switch. Home can run a Play services settings check when the user taps **Fix**. Delivery can degrade with GPS or Wi-Fi scanning disabled. **Future.** Show guidance for degraded accuracy and retry pending registrations when location becomes available.
