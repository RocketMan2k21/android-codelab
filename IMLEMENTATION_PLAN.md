Plan for test task.
1. Provide dependency for maps
2. Setup Permissions
2. Make form support location input (choosing the location on map/search location through search bar)
3. Create LocationManager with following contract:
    - current location
    - location permission status
4. Setup Geofencing Api
5. Setup NotificationManager
6. Tests: Unit&Integration tests , Room

Flow:
1. User creates new note -> Chooses location -> Saves note -> Persist To Room -> Creates Geofence Request -> User reaches location -> Notification shown ->  User taps on notification -> Opens the reminder details/info screen

Caveats: Geofence supports max of 100
- Need to ensure priority
1. What if user creates 100+ reminders with location?
2. What if user disabled/changed location permission after creating location reminder?
3. What is user create many reminder with the same/close locations?
4. Rebooting
5. Turned off Wifi/Wifi only scan mode and GPS

Priorities
Priority	Reminder
1	User explicitly marked as important
2	Recently created reminders
3	Reminders with upcoming scheduled dates
4	Older reminders