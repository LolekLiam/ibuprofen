# ibuprofen
Odprtokodna aplikacija za Android, ki omogoča vpogled v urnike eAsistenta

# Features

- Material 3 interface with light, dark and system themes.
- Public eAsistent timetables for multiple schools, with class, teacher and room views.
- Week navigation and clear lesson cards.
- eAsistent mobile account sign-in, personal timetables and grades.
- Configurable lesson reminders.
- Optional self-hosted proxy with timetable caching and direct upstream fallback.
- Server administration shown when the proxy is enabled: separate admin sign-in, school registration and synchronization progress.

# Building and self-hosting

Open this project in Android Studio, configure your Android SDK, and build the `app` module. You can also build a debug APK with:

```sh
./gradlew :app:assembleDebug
```

The APK is generated in `app/build/outputs/apk/debug/`. The proxy is optional; see [ibuprofen API](https://github.com/LolekLiam/ibuprofen-api) for server installation and app configuration. HTTP LAN URLs and HTTPS URLs are supported.

# License

[AGPL-3.0](LICENSE).

# Credits
[@MaticBabnik](https://github.com/MaticBabnik) za [easistent-tt](https://github.com/MaticBabnik/easistent-tt)

[@chocoearly44](https://github.com/chocoearly44) za [JEA](https://github.com/chocoearly44/JEA)

Special thanks to eAsistent dev team za NIČ ker nimate javnega API-ja in ker gatekeepate naše redovalnice

# TODO
- ~fix ui~
- ~optimize~
- ~fix ux~
- ~add support for other schools~(some1 test please?)
- ~add easistent auth~
- ~fuck easistent, free redovalnice za vse~
- switch few endpoints from mobile to web
- add support for parent login
- see upcoming tests
- suggestions?

# Disclaimer
This project was created by observing publicly visible network traffic in the eAsistent web application.
No proprietary code, assets, or private API keys were used or reverse-engineered.
All communication happens entirely through publicly accessible endpoints.

This project is not affiliated with or endorsed by eŠola d.o.o.
This app merely provides an alternative interface for users to view their own educational data.
Personal account communication occurs directly between the user and the official eAsistent servers. Public timetables can optionally pass through a self-hosted proxy configured by the user.
No credentials or personal data are sent to third-party servers.
Users are responsible for ensuring their use complies with their school's policies.
All trademarks belong to their respective owners.
