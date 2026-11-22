# TeleTube

TeleTube scans the broadcast channels in your Telegram account for YouTube videos. Use **Scan channels** to choose all joined channels or a subset, including archived channels. The feed groups links by the original Telegram post date and lets you filter by channel. Tap a video to open it or use the row menu to open its source post.

## Inspiration

I’d heard that Andrej Karpathy vibe-coded a YouTube clone in a day using recommendations from Hacker News.

I use Telegram a lot to keep up with tech news and ideas. When I’m scrolling through my feed, I often see a YouTube link that looks interesting, but I don’t have time to watch it right then. Later, when I’m about to head out on an hour-long drive and want something to listen to, I have no idea how to find those links again.

Then it clicked. I could vibe-code a little app that does the same thing with my Telegram channels, pulling all those YouTube links into one feed.

Done!

## Run on Android

1. Assume Telegram is installed. TeleTube requires Android 8.0 or newer.
2. [Download the latest TeleTube APK](https://github.com/tprlab/teletube/releases/latest/download/teletube-release.apk), then open the file on your Android device and install it.
3. Open TeleTube and sign in with your existing Telegram account. Telegram may ask for a verification code and your two-step verification password.

The first scan covers the last 30 days. Refreshes check for newer posts and resume interrupted scans from their saved checkpoints. Pull down on the feed to refresh. You can browse and filter saved links during a scan, or choose **Stop scan** from the main menu.

Tap the channel filter in the feed, check any channels you want to see, and tap **Apply filter**. Choose **All channels** and tap **Show all channels** to clear the filter. Viewed videos are shown by default. Tap the eye icon to hide them.

Use **Hide video** in a video’s menu to remove that video from the feed across all channels, or **Hide channel** to remove the whole channel from the feed and filter picker. Open **Hidden** from the main menu to restore a video or channel. Hiding keeps saved links, viewing history, and scan settings.

Playing a video from TeleTube marks it as viewed. Open **History** from the main menu to see each playback, newest first, or to play a video again.

The feed, scan settings, and Telegram session stay on your device. Signing out clears them. The app uses Compose, Room, and the pinned TDLib Android AAR from Maven Central.

## Build from source

Open the project in Android Studio with JDK 17 or 21 and Android SDK 35. Run the `app` configuration or use `./gradlew :app:installDebug`.

For a build from source, register a Telegram application under **API development tools** at [my.telegram.org](https://my.telegram.org/apps). You can enter its API ID and API hash on TeleTube’s setup screen or configure them in the project’s ignored `local.properties` file using `telegram.apiId` and `telegram.apiHash`. Gradle embeds configured values in the APK, where they can be extracted.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Build a signed APK on GitHub Actions

Create an environment called `release` under **Settings → Environments**. Limit deployments to the `main` branch and trusted `v*` tags. Add these environment secrets.

| Secret | Value |
| --- | --- |
| `TELEGRAM_API_ID` | TeleTube’s Telegram API ID |
| `TELEGRAM_API_HASH` | TeleTube’s Telegram API hash |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Release keystore password |
| `ANDROID_KEY_ALIAS` | Alias of the signing key in the keystore |
| `ANDROID_KEY_PASSWORD` | Signing key password |

If you have a release keystore, you can send it directly to GitHub with `base64 < /path/to/release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env release`. Keep a secure backup and use the same key and alias for future updates.

Once the workflow is on the default branch, open **Actions → Build release APK → Run workflow** and select `main`. To build a specific version tag that contains the workflow, run `gh workflow run release-apk.yml --ref v0.1.0`.

The run produces a `teletube-release` artifact with the signed APK and its SHA-256 checksum. Test the APK before attaching both files to a GitHub Release. Increment `versionCode` for each app update.

Anyone with access to the APK can extract the embedded Telegram API credentials. The signing keystore and passwords are used only to sign the APK.
