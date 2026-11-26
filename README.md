# dojazd

Open-source Polish public transport app: timetables, live departures, a vehicle map and a journey planner.

## Install

[<img src=".github/badges/obtainium.png" alt="Get it on Obtainium" height="56">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22benqsz.dojazd%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbenqsz%2Fdojazd%22%2C%22author%22%3A%22benqsz%22%2C%22name%22%3A%22Dojazd%22%7D)
[<img src=".github/badges/github.png" alt="Get it on GitHub" height="56">](https://github.com/benqsz/dojazd/releases/latest)

## Principles

- **No account, no server, works offline.** The app talks directly to the transit operator's open data; there is no Dojazd backend, and every request the app makes is listed in its settings. The timetable is kept on the device and the city's addresses, map and streets ship with the app, so journeys are planned, addresses searched, walks measured and the map drawn without a connection. In offline mode the app never goes online at all: you import the timetable from a file.
- **Live data only when you look at it.** Delays, vehicle positions and alerts are fetched from the operator's GTFS-RT feeds only while a screen shows them, never in the background. They add to the timetable; without them the app shows scheduled times. They can be switched off in the settings.
- **Your places stay on your phone.** Favourite places (home, work and the like) live in a local JSON file. Cloud backup is disabled; you can export and import the file yourself. Your location is read from the phone's own location service, only when you choose "My location", and never leaves the device.
- **Open source only.** No Google Play Services, no analytics, no crash reporting to third parties.
- **Local notifications only.** Departure reminders and get-off alerts are scheduled on the device. Server push (UnifiedPush/FCM) was considered and dropped: it would need a backend and would only make disruption alerts arrive a few minutes sooner.
- **No ticket sales.** They require contracts with the city and a payment operator.

## Supported cities

- **Szczecin**: [ZDiTM Szczecin](https://www.zditm.szczecin.pl/), [GTFS and GTFS-RT](https://www.zditm.szczecin.pl/en/zditm/for-developers/gtfs), CC0 1.0

A city that publishes its timetable as GTFS can be added without writing code: see [Adding a city](Add_city.md).

## Roadmap

1. **Local notifications**: departure reminders, trip mode, watched lines, commute windows and a widget.
2. **More cities**
3. **Google Play**
4. **Accrescent**
5. **iOS**

## Build

Requires a JDK to start Gradle and the Android SDK. The build runs on JDK 25.

```sh
gradlew build                          # compile, test, check formatting, lint and library boundaries
gradlew spotlessApply                  # format the code
gradlew :androidApp:installDebug       # install on a connected device or emulator
gradlew :androidApp:installOptimized   # the release build signed with the debug key
```

## Signing certificate

```text
benqsz.dojazd
AF:2C:D6:19:00:9F:7B:9E:E9:F9:9D:88:60:2F:76:FD:8C:F8:BD:F5:1F:ED:CA:56:D1:8F:3E:11:A6:FE:07:82
```

## License

Copyright (C) 2026 benqsz

The code is free software under the [GNU General Public License, version 3](LICENSE) only.  
The data and the map that ship with the app keep their own licenses: see [data/LICENSE](data/LICENSE) and [androidApp/src/main/assets/map/LICENSE](androidApp/src/main/assets/map/LICENSE).
