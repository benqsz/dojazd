# dojazd

Open-source Polish public transport app: timetables, live departures, a vehicle map and a journey planner.

## Install

[<img src=".github/badges/obtainium.png" alt="Get it on Obtainium" height="56">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22benqsz.dojazd%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbenqsz%2Fdojazd%22%2C%22author%22%3A%22benqsz%22%2C%22name%22%3A%22Dojazd%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22sortMethodChoice%5C%22%3A%5C%22date%5C%22%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22releaseTitleAsVersion%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22dojazd-%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22minimumUpdateAgeDays%5C%22%3A%5C%22%5C%22%2C%5C%22appName%5C%22%3A%5C%22Dojazd%5C%22%2C%5C%22appAuthor%5C%22%3A%5C%22benqsz%5C%22%2C%5C%22shizukuPretendToBeGooglePlay%5C%22%3Afalse%2C%5C%22allowInsecure%5C%22%3Afalse%2C%5C%22allowedSigningCertHashes%5C%22%3A%5C%22AF%3A2C%3AD6%3A19%3A00%3A9F%3A7B%3A9E%3AE9%3AF9%3A9D%3A88%3A60%3A2F%3A76%3AFD%3A8C%3AF8%3ABD%3AF5%3A1F%3AED%3ACA%3A56%3AD1%3A8F%3A3E%3A11%3AA6%3AFE%3A07%3A82%5C%22%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22Open-source%20Polish%20public%20transport%20app%5C%22%2C%5C%22refreshBeforeDownload%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22zippedApkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22includeTarballs%5C%22%3Afalse%2C%5C%22tarballedApkFilterRegEx%5C%22%3A%5C%22%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D)
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
