# Lava

Lava is an Android mobile app that is an unofficial client for popular
Russian torrent trackers. As of **1.2.0** Lava is multi-tracker — the app
ships with built-in support for **RuTracker** (rutracker.org) and
**RuTor** (rutor.info / rutor.is), with a pluggable SDK that makes adding
a third tracker an isolated module change. Users can search and download
torrent files, view topic detail and comments, manage their downloads,
and switch between trackers from a single Settings screen.

Lava is designed to provide a fast, intuitive, and seamless user experience
on the go. The app is open source and free to use, with no ads or tracking,
and is constantly being updated and improved by a small community of
developers and contributors.

Disclaimer: Lava is not affiliated with or endorsed by rutracker.org,
rutor.info, or any other tracker site. The app is provided for personal,
non-commercial use only, and users are responsible for complying with all
applicable laws and regulations regarding copyright and file sharing.

[**For copyright owners**][1]

## Supported trackers (Lava-Android-1.2.0)

| Tracker   | Module                       | Capabilities                                                              | Auth                                |
|-----------|------------------------------|---------------------------------------------------------------------------|-------------------------------------|
| RuTracker | `:core:tracker:rutracker`    | SEARCH + BROWSE + FORUM + TOPIC + COMMENTS + FAVORITES + DOWNLOAD + MAGNET + AUTH (CAPTCHA) + UPLOAD + USER_PROFILE | Required (login + captcha)         |
| RuTor     | `:core:tracker:rutor`        | SEARCH + BROWSE + TOPIC + COMMENTS + DOWNLOAD + MAGNET + RSS + AUTH        | Anonymous by default (decision 7b-ii) |

The active tracker is user-selectable from **Settings → Trackers**. Each
tracker carries its own bundled mirror list, supports user-added custom
mirrors, runs a 15-min mirror health probe, and falls back to the
alternative tracker via an explicit user-prompted modal when all of its
own mirrors go UNHEALTHY (no silent fallback).

To add a third tracker (or fork the SDK to add a private one), follow the
seven-step recipe in [`docs/sdk-developer-guide.md`](docs/sdk-developer-guide.md).

## Mobile app

[<img src="badges/google-play-badge.png" alt="Get it on Google Play" height="90">][2]
[<img src="badges/github-badge.png" alt="Get it on GitHub" height="90">][3]
[<img src="badges/rustore-badge.png" alt="Get it on RuStore" height="90">][4]

## Screenshots

<details>
    <summary>Light</summary>

<img src="screenshots/search_history_light.png" alt="Search" width="250">
<img src="screenshots/search_result_screen_light.png" alt="Search" width="250">
<img src="screenshots/forum_light.png" alt="Forum" width="250">
<img src="screenshots/topic_light.png" alt="Topic" width="250">

</details>

<details>
    <summary>Dark</summary>

<img src="screenshots/search_history.png" alt="Search" width="250">
<img src="screenshots/search_result_screen.png" alt="Search" width="250">
<img src="screenshots/forum.png" alt="Forum" width="250">
<img src="screenshots/topic.png" alt="Topic" width="250">

</details>

## Contact

Feel free to contact us if you found this useful or if there was something that
didn't behave as you expected. We can't fix what we don't know about, so please
report liberally. If you're not sure if something is a bug or not, feel free to
file a bug anyway.

**Issue tracker:** <https://github.com/milos85vasic/Lava/issues>

**Forum 4PDA:** <https://4pda.to/forum/index.php?showtopic=729411>

## Sources

All the code and the content is available on github: <https://github.com/milos85vasic/Lava>

## Privacy Policy

[Privacy Policy][5]

## License

This software is open source, licensed under the [MIT License][6].

[1]: https://lava-app.tech/copyrights.html

[2]: https://play.google.com/store/apps/details?id=digital.vasic.lava.client

[3]: https://github.com/milos85vasic/Lava/releases

[4]: https://apps.rustore.ru/app/digital.vasic.lava.client

[5]: https://lava-app.tech/privacy-policy.html

[6]: https://opensource.org/licenses/MIT 
