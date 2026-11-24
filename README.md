# android-codelab
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

- Activities render XML layouts with View Binding. ViewModels coordinate UI state and call use cases for memo creation and monitoring.
- `App` creates shared dependencies lazily. ViewModel factories use manual constructor injection. The Room repository, geofence manager, fused location source, and location settings checker expose interfaces.

## Geofence reminder flow

1. Select a point on the map and save the memo. The app checks reminder prerequisites and writes the memo to Room before attempting registration.
2. When the prerequisites are met, Play services registers a 200 m geofence keyed by the memo ID. It reports entry events and can also report an initial entry if the device is already inside the zone.
3. `GeofenceBroadcastReceiver` looks up the matching open memo. The notification shows its title, the first 140 characters of its text, and an icon. Tapping it opens the memo.

## Limitations & Future Improvements

Geofence events are asynchronous. Crossing the 200 m boundary does not guarantee an immediate notification, especially in the background.

- **Geofence limit.** With all 100 geofence slots in use, an additional memo is saved in Room and its registration fails. **Future.** Keep excess reminders pending, prioritize active slots by memo priority, and promote pending reminders as slots free up.
- **Permission changes.** Home rechecks precise and background location access when it resumes. Revoking access can leave the registration flags stored in Room stale. If access is missing during creation, the user can still save the memo without a reminder. **Future.** Mark affected reminders as suspended and reconcile registrations after access is restored.
- **Nearby geofences.** Each memo has its own geofence, so identical or overlapping locations can produce multiple notifications. **Future.** Share a geofence for identical points and group notifications for overlapping points while preserving each memo's trigger area.
- **Device reboot.** Memos and their registration flags persist in Room, while geofence registrations are lost. **Future.** On `BOOT_COMPLETED`, reload eligible open memos, register them again, and update the stored flags from the results.
- **Location settings.** The app checks the device-wide Location switch. Home can run a Play services settings check when the user taps **Fix**. Delivery can degrade with GPS or Wi-Fi scanning disabled. **Future.** Show guidance for degraded accuracy and retry pending registrations when location becomes available.
