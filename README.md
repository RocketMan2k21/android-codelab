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

The app targets Android 13 (API 33) and supports Android 8.1 (API 27) and later. Targeting API 33 lets the app request notification permission when the user saves a location reminder. Background location remains a separate permission. Android 10 offers it in the system permission dialog, and Android 11 and later offer it in the app's location permission settings.

## Dependency injection

The app uses manual constructor injection. Android creates `App` from the manifest. `App.onCreate()` initializes the process-wide `RoomMemoRepository` and creates the shared `GeofenceNotificationManager`. The two use cases are created lazily and reused for the lifetime of that application process. `Home`, `CreateMemo`, and `ViewMemo` use AndroidX `ViewModelProvider` factories to supply dependencies to their Activity-scoped ViewModels.

### Shared dependencies

| Consumer | Dependencies and ownership |
| --- | --- |
| `CreateMemoUseCase` | `App` passes `RoomMemoRepository` as `MemoRepository` and a `PlayServicesGeofenceManager` as `GeofenceManager`. |
| `MonitoringUseCase` | `App` passes the same repository, a separate `PlayServicesGeofenceManager`, a `LocationPermissionSource`, and the shared `GeofenceNotificationManager`. The use case reads the current location permissions, device location setting, and notification access from those sources when it builds `ReminderReadiness`. |
| `RoomMemoRepository` | The repository builds the Room `Database` from the application context and delegates memo queries and writes to Room's generated `MemoDao`. |
| `PlayServicesGeofenceManager` | Each instance obtains a Play Services geofencing client from the application context. It registers and removes geofences by memo ID and directs transition broadcasts to `GeofenceBroadcastReceiver`. |
| `GeofenceNotificationManager` | The shared manager uses the application context and `NotificationManagerCompat` to read notification access, manage the reminder channel, and post reminders. |

### Screens and background delivery

| Entry point | Dependency wiring |
| --- | --- |
| `Home` | The Activity's factory calls `HomeViewModel.create(app)`. That method passes `RoomMemoRepository` and the shared `MonitoringUseCase`. The ViewModel reads memo lists through the repository and uses the use case for monitoring status, activation, and completion. |
| `CreateMemo` | The Activity's factory passes an AndroidX `SavedStateHandle` and the shared `CreateMemoUseCase` to `CreateMemoViewModel`. The Activity reads permission and settings state through its own `LocationPermissionSource`, the system location service, and the shared notification manager. It passes the resulting `ReminderReadiness` to the ViewModel. |
| `ViewMemo` | The Activity reads the memo ID from the launch intent and passes it, `RoomMemoRepository`, and the shared `MonitoringUseCase` to `ViewMemoViewModel`. The ViewModel loads details from the repository and uses the use case to mark the memo done. |
| `MapActivity` | The Activity lazily creates its own `LocationPermissionSource` and passes it to `FusedLocationDataSource`, which implements `LocationDataSource` using Play Services. Google Maps supplies the map through `SupportMapFragment`. The selected coordinates return to `CreateMemo` through the Activity result. |
| `LocationPermissionLauncher` | `Home`, `CreateMemo`, and `MapActivity` create this Activity result contract locally. For a foreground request, the contract creates a `LocationPermissionSource` with the supplied context to record that the request was made. |
| `GeofenceBroadcastReceiver` | Android creates the receiver. It gets `App` from the broadcast context, reads the matching open memos directly from `RoomMemoRepository`, and passes them to `App.geofenceNotificationManager`. The notification carries the memo ID into `ViewMemo` via its launch intent. |

The use cases share the `memoGeofenceChanges` mutex, which serializes memo creation, monitoring activation, and completion changes even though each use case has its own geofence manager instance.

### Unit tests

The JVM tests call the same use case constructors with `InMemoryMemoRepository` and `RecordingGeofenceManager`. The monitoring tests also use Mockito to supply permission and notification state and to intercept Android logging.
