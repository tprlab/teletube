# TeleTube

TeleTube scans the broadcast channels in your Telegram account for YouTube videos. Use **Scan channels** to choose all joined channels or a subset, including archived channels. The feed groups links by the original Telegram post date and lets you filter by channel. Tap a video to open it or use the row menu to open its source post.

## Run on Android

1. Install Telegram and the TeleTube debug APK on Android 8.0 or newer. To build TeleTube yourself, open the project in Android Studio with JDK 17 or 21 and Android SDK 35, then run the `app` configuration or `./gradlew :app:installDebug`.
2. Create a Telegram application under **API development tools** at [my.telegram.org](https://my.telegram.org/apps). Enter its API ID and API hash on TeleTube’s setup screen.
3. Sign in with your Telegram phone number. Telegram chooses how to deliver the verification code and may prompt for your two-step verification password.

For local development you can set default credentials in the project’s ignored `local.properties` file. Use `telegram.apiId` for the numeric ID and `telegram.apiHash` for the 32-character hash. Gradle embeds those values in the APK. For builds you plan to share, omit both settings so users enter their own credentials on the setup screen.

The first scan covers the last 30 days. Refreshes check for newer posts and resume interrupted scans from their saved checkpoints. Pull down on the feed to refresh. You can browse and filter saved links during a scan, or choose **Stop scan** from the main menu.

Tap the channel filter in the feed, check any channels you want to see, and tap **Apply filter**. Choose **All channels** and tap **Show all channels** to clear the filter. Viewed videos are shown by default. Tap the eye icon to hide them.

Use **Hide video** in a video’s menu to remove that video from the feed across all channels, or **Hide channel** to remove the whole channel from the feed and filter picker. Open **Hidden** from the main menu to restore a video or channel. Hiding keeps saved links, viewing history, and scan settings.

Playing a video from TeleTube marks it as viewed. Open **History** from the main menu to see each playback, newest first, or to play a video again.

The feed, scan settings, and Telegram session stay on your device. Signing out clears them. The app uses Compose, Room, and the pinned TDLib Android AAR from Maven Central.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```
