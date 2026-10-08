<p align="center">
  <img src="docs/assets/app_icon.png" width="120" height="120" alt="Athr+ Logo" />
</p>

<h1 align="center">Athr+</h1>

<p align="center"><b>An independent companion app and home-screen widget for your electric scooter.</b></p>

## Download and update

**[Download the latest release](https://github.com/karmugilen/athr-plus/releases/latest)** · **[v1.1.17 release notes](https://github.com/karmugilen/athr-plus/releases/tag/v1.1.17)**

Requires Android 8.0 or newer. Download the `Athr+-v1.1.17-release.apk` asset and install it over your existing Athr+ app. Android may ask you to allow installation from your browser or file manager. Keep the existing app installed to retain your login, settings, and history.

Version **1.1.17 (build 20)** is a signed, non-debuggable release with R8 code optimization and resource shrinking. Its signing certificate matches the previous public v1.1.2 APK; the APK file checksum changes with each release.

This version adds rider insights, ride-route history, clearer charging controls, direct phone-location updates, and app-wide refresh-rate requests up to 144 Hz on supported devices. Scooter snapshot checks remain every five seconds. Users on v1.1.16 can use **Settings → App updates → Check now** to see this release, review the notes, and choose whether to install it. Automatic checks run on opening the app at most once every six hours and approximately daily in the background; Android may delay background work. A source push alone does not trigger an app update. Users on v1.1.2 need to install an updater-enabled release manually once.

## Support the project

If you find the app useful, consider supporting the project.

**UPI:** `karmugilrc-1@okaxis`

In the app, open **Settings → Support the project → Support via UPI** to choose an installed UPI payment app. You can also copy the UPI ID and pay directly in your preferred app.

## Why This Project Exists

I built **Athr+** because the official app became frustrating to use:

- **Missing from Play Store**: If your phone has an unlocked bootloader or runs a custom ROM, the official app doesn't show up in the Google Play Store.
- **Developer Options Block**: If you keep Developer Options enabled, the app constantly stops you with "Turn off developer options" warnings.
- **Paid for Pro, Couldn't Use It**: Even after paying for the official Ather Pro pack, the app gave a frustrating experience with artificial device blocks.

So I created and open-sourced **Athr+** to give everyone full, unrestricted access to their own scooter—live battery status, smart charging limits, home screen widgets, and ride history—without any annoying device blocks.

## Features

- **Battery and scooter information:** battery percentage, charging state, location, odometer, and per-mode range when supplied by the vehicle API. Active monitoring requests snapshots every five seconds; the scooter/cloud may return older readings, so requests do not guarantee fresh data.
- **Charge limiter:** choose a target percentage and see an estimated stop time. Monitoring can request a stop when the measured level reaches the target or the saved estimated deadline arrives. Estimates are approximate, and stopping depends on Android background execution, connectivity, and the scooter accepting the command.
- **24-hour battery history:** local measured samples with source timestamps; repeated cached readings do not become invented new measurements.
- **Material 3 appearance:** system light/dark mode and wallpaper-derived colors on Android 12 and newer, with a Material palette on older phones.
- **Compact home-screen widget:** battery, current range, clear per-mode range rows, sync time, connection status, and a limiter bar when enabled. Widget colors follow system appearance changes.
- **Model-independent battery artwork:** an animated battery while charging and the original scooter launcher icon.
- **Rust calculations:** native history selection, range scaling, and charging estimates for ARM and x86 devices.
- **Phone sign-in:** searchable country calling codes (India +91 by default), international number paste, country-aware validation, and SMS code resend with a countdown. OTP delivery for each country depends on the account provider.
- **Responsive phone location:** requests high-accuracy updates every second while the map is open and displays accepted fixes immediately. Shows Android coordinates directly without custom accuracy thresholds, movement filtering, or averaging. See [location behavior](docs/FIND-SCOOTER.md).
- **Smooth scrolling and headings:** display-paced heading animation and app-wide requests for the fastest supported refresh rate up to 144 Hz while resumed. Actual frame rate depends on the device, Android settings, and WebView.
- **Maps and ride analytics:** Leaflet street maps, vehicle heading, local trip history, and available battery/efficiency information.
- **Rider insights:** observed charging session history, parked battery change, and a daily-distance battery estimate with a configurable reserve.
- **Automatic tyre-check reminders:** learns comparable ride-efficiency patterns and suggests checking tyres after sustained deterioration. Requires no TPMS or manual pressure entry; cannot measure PSI or diagnose a puncture. Also shows observed speed–efficiency bands when enough comparable rides exist. See [how insights work](docs/RIDER-INSIGHTS.md).
- **Verified app updates:** public GitHub release checks without a GitHub login; downloaded APKs are checked for package, version, signing certificate, size, and available checksum before installation.

## Screenshots

These screenshots show an earlier release. Version 1.1.15 updates the theme, battery artwork, and widget layout.

| Dashboard | Charge limit | Street map |
| :---: | :---: | :---: |
| <img src="docs/assets/screenshot_dashboard.png" width="240" alt="Earlier dashboard" /> | <img src="docs/assets/screenshot_charging.png" width="240" alt="Earlier charge limiter" /> | <img src="docs/assets/screenshot_map.jpg" width="240" alt="Street map" /> |

<details>
<summary>Earlier home-screen widget</summary>
<img src="docs/assets/screenshot_widget.png" width="420" alt="Earlier home-screen widget" />
</details>

## Build

Requires JDK 17 or 21, Android SDK API 34, Build-Tools `34.0.0`, NDK `26.1.10909125`, and Rust with these targets:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
```

Configure `android/local.properties` with your SDK path (`sdk.dir=/path/to/Android/Sdk`), or set `ANDROID_HOME`. Local properties and generated build files are ignored by Git.

```sh
# Development build
JAVA_HOME=/path/to/jdk-21 ./android/gradlew -p android assembleDebug --console=plain

# Optimized public build: configure the original signing key first.
# See docs/APP-UPDATES.md for the four signing environment variables.
JAVA_HOME=/path/to/jdk-21 ./android/gradlew -p android assembleRelease --console=plain
JAVA_HOME=/path/to/jdk-21 python3 scripts/prepare-release.py android/app/build/outputs/apk/release/app-release.apk
```

The release APK is generated at `android/app/build/outputs/apk/release/app-release.apk`. The packaging script verifies its signing certificate and release identity, scans for locally known private account values, and creates the APK, SHA-256 checksum, and `update.json` under `android/app/build/release-upload/`.

See [publishing and verifying updates](docs/APP-UPDATES.md) for local release steps and optional GitHub Actions signing. Release signing requires the original app key to preserve upgrade compatibility. Keep that key outside Git.

## Development

The app uses Kotlin 2.0, Jetpack Compose, Material 3, OkHttp, Gson, Room, WorkManager, and Rust through JNI. Leaflet runs inside an Android WebView.

```text
android/app/src/main/java/io/ather/pro/
  data/           API clients, local storage, Rust bridge, app updates
  domain/         Charging rules, battery history, ranges, data models
  presentation/   ViewModels and screen state
  service/        Monitoring and background work
  ui/             Compose screens, themes, and navigation
  widget/         Home-screen widget snapshots and rendering
rust/ather-math/   Native calculation library
scripts/          Local API tools, release packaging, credential guards
docs/             Feature and publishing documentation
```

Optional developer checks:

```sh
./android/gradlew -p android test
./android/gradlew -p android lintRelease
```

For desktop OTP login, telemetry, and charging API experiments with credentials stored outside Git, see [the desktop API lab](docs/LOCAL-API-LAB.md). Do not share session files or include them in a release.

## Privacy and credentials

Scooter sign-in uses your OTP, and session data stays in local app storage. The app uses Android Keystore-backed encrypted preferences where available, with a private preferences fallback if encrypted storage cannot be initialized. GitHub update requests do not use scooter credentials.

Keystores, local properties, APKs, and local API session files are excluded from Git. The local commit/push guard checks private file paths, credential patterns, and known local account values. These safeguards complement reviewing changes before publishing; they are not a substitute for that review.

## Legal Notice & Disclaimer

> [!NOTE]
> **Notice**: This application was developed independently using exclusively publicly available information, standard open network protocols, and resources accessible online. It contains no proprietary source code, confidential intellectual property, or trade secrets.
>
> This project is completely independent and is not affiliated with, authorized, maintained, sponsored, or endorsed by any vehicle manufacturer, automotive brand, or corporate entity. All trademarks, service marks, trade names, and product names are the property of their respective owners. The software is provided solely for personal interoperability, research, educational, and hobbyist purposes.
