# F-Droid packaging

TeleTube’s application ID is `com.tprlab.teletube`. Its source is MIT-licensed. The first proposed F-Droid release is `v0.1.1` with Android version code `2`. The store listing is in `fastlane/metadata/android/en-US/`.

## Build and verify

From a clean checkout of the release tag, use JDK 17 and Android SDK 35. Configure the Android SDK location as usual and leave `telegram.apiId` and `telegram.apiHash` unset. Run `./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`. The APK is `app/build/outputs/apk/release/app-release-unsigned.apk`.

After signing and installing the APK on a test device with a Telegram-compatible client installed, enter an API ID and hash from `my.telegram.org/apps` on the setup screen. Sign in to Telegram, scan a joined broadcast channel with a YouTube link, and open the result from the feed.

## Notes for F-Droid reviewers

TeleTube needs Telegram’s servers to scan the user’s joined broadcast channels. Proposed Anti-Feature is `NonFreeNet`. The app’s startup check requires an installed Telegram-compatible client that handles `tg://resolve`. YouTube links open in an external app or browser.

Gradle pulls the pinned [TDLib Android AAR](https://central.sonatype.com/artifact/io.github.tdlib-android/core/0.1.1) from Maven Central. It contains prebuilt native libraries. The [AAR packager publishes its build source](https://github.com/AkashPriyadarshii/tdlib-android), and the repository’s [third-party notices](../THIRD_PARTY_NOTICES.txt) cover TDLib and its bundled OpenSSL. Please confirm whether F-Droid can use this artifact or requires building the native libraries from source.

## Signing

Use F-Droid’s signing key for the main-repository build. The F-Droid build uses user-supplied Telegram API credentials, while the signed GitHub release embeds TeleTube’s API credentials through the release workflow. Users can update normally within each channel. Switching between the GitHub and F-Droid builds requires uninstalling the installed app first, which clears the local feed, viewing history, and Telegram session.
