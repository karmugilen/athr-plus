# GitHub app updates

Athr+ checks the latest stable release of `karmugilen/athr-plus` on opening the app (at most once every six hours) and approximately daily through WorkManager. Android can delay background checks. Settings → App updates also has **Check now**. A new version displays a banner and one notification per release if notifications are allowed. No account login or GitHub token is used for these requests.

Users review the release notes, tap **Download update**, and then **Install update**. The download must match its size, checksum when supplied, application ID, version and installed signing certificate. Android may ask them to allow installations from Athr+ and always shows its installation confirmation. The update retains app data. Downloads occur only on request; leaving the screen keeps downloading while the process lives. A killed process requires a fresh download.

Only users who have installed a version containing this updater get these notices. Users on the old v1.1.2 must install the first updater-enabled release manually once. A normal source push does not notify phones; a **published stable GitHub Release with an APK** does. Drafts and prereleases are ignored. Mark the desired release as **Latest**.

## Publishing subsequent versions

1. Increase `versionCode` in `android/app/build.gradle.kts` above every distributed build, including local builds. The current release v1.1.17 is code **20**, so the next update must be at least **21**. Use a stable version tag such as `v1.1.18`.
2. Build and package locally with the original signing key, commit your reviewed app changes, and push the version tag. Upload the APK, checksum, and `update.json` to a **draft** release. Alternatively, the optional signing workflow prepares the draft when configured as described below.
3. Review the draft’s notes and APK, then publish the release as **Latest**. Apps detect it on their next automatic or manual check.

The GitHub Actions signing workflow is disabled by default. To enable it, set the repository Actions variable `ATHR_CI_SIGNING_ENABLED` to `true` and configure the following Actions secrets. The workflow builds and attaches files to a draft; it does not publish the release. Local publishing does not require uploading the signing key or setting this variable.

| Setting | Value |
| --- | --- |
| Actions variable `ATHR_CI_SIGNING_ENABLED` | `true` |

Required Actions secrets:

| Secret | Contents |
| --- | --- |
| `ATHR_KEYSTORE_BASE64` | Base64 of the **original** app signing keystore |
| `ATHR_SIGNING_STORE_PASSWORD` | Original keystore password |
| `ATHR_SIGNING_ALIAS` | Original signing alias |
| `ATHR_SIGNING_KEY_PASSWORD` | Original key password |

The existing public v1.1.2 and current local APK share certificate SHA-256 `9c33627be30850a9fd315b374896b5fe229bd8c09c51e0d7a77583cd6d3a38f3`. The original release used this machine’s Android debug signing key. Preserve that exact key for existing users. Generating a new debug key on a CI runner breaks Android updates. A future key rotation requires a separate supported migration. Never commit keystores or put scooter tokens into Actions secrets. The packaging script rejects APKs signed with another certificate.

Releases through v1.1.17 are built and published locally. No signing key or scooter session has been uploaded to GitHub Actions. Keep using local packaging unless you explicitly choose to enable CI signing.

The signing certificate identifies the publisher and is public. The APK SHA-256 identifies the exact file and changes with every build. The original key has an Android debug certificate name, but this release uses the non-debuggable, optimized **release** build type. Keeping that original key preserves updates for existing users.

## Preparing a release locally

Set `ATHR_SIGNING_STORE` to the original keystore’s absolute path (on the current machine, `~/.android/debug.keystore`), plus the three alias/password variables above. Keep these in your shell or a file outside the repository. Then:

```sh
# Example for the next release, after increasing the source versionCode to 21:
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./android/gradlew -p android assembleRelease \
  -PathrVersionName=1.1.18 --console=plain
JAVA_HOME=/usr/lib/jvm/java-21-openjdk python3 scripts/prepare-release.py android/app/build/outputs/apk/release/app-release.apk
```

Upload the three files from `android/app/build/release-upload/` to a draft release tagged `v1.1.18`, then publish it as Latest. The packaging script never pushes or uploads anything. `update.json` supplies the Android version code so local builds and release builds compare correctly. Older releases without that metadata use stable numeric tag comparison, then verify the APK’s actual version before installation.

Release optimization enables R8 minification, optimization, and resource shrinking. Persistence model fields and Rust JNI entry points are preserved so optimization does not rename stored JSON keys or native method names. Artifact checks verify a higher version code, the original signing certificate, and that the APK is not debuggable.

Documentation: [GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [Android secure file sharing](https://developer.android.com/training/secure-file-sharing/setup-sharing).

Before upload, the local source/push guard and APK packaging check scan for saved account credentials, JWT/Bearer literals, common API-key formats, and private signing keys. APK account-value checks include UTF-8 and UTF-16 strings. Checks report paths and reasons without printing matched secrets. Signing keys and local session/capture files remain outside the release.
