# YouTube subscriptions, playback, and downloads

This fork adds a manually maintained channel list to mpv-android. It uses public YouTube channel pages and RSS feeds for recent uploads, and yt-dlp for media resolution and downloads. It does not sign into Google, synchronize an account's subscriptions, or use the YouTube Data API.

The launcher opens the YouTube screen directly. **Feed** shows recent uploads with thumbnails, **Channels** manages followed channels, and **Downloads** shows saved and active jobs. The toolbar offers channel and video-link actions; its overflow menu opens **Local media** and the yt-dlp update action.

The interface uses flat neutral surfaces, square corners, and no outlines, following the requested visual direction. Thumbnails and titles take priority, with compact navigation and clear playback and download actions. The emphasis on content and simple navigation draws on [YouTube's design principles](https://blog.youtube/inside-youtube/design-principles-use-put-creators-center-stage/) and [Netflix's mobile design](https://about.netflix.com/en/news/introducing-exciting-new-ways-to-find-and-enjoy-your-next-favorite-on-mobile); the strict square treatment is specific to this fork.

Feed and list content starts directly beneath the tabs. Persistent section headings, last-refresh timestamps, repeated tab titles, and subtitles are omitted to keep interface copy minimal.

## Following channels

1. Open the app and choose **Channels**.
2. Add a channel link, such as `https://www.youtube.com/@handle` or `https://www.youtube.com/channel/UC…`. Legacy `/c/` and `/user/` channel links are also accepted.
3. Refresh to fetch the latest uploads for your saved channels. Remove a channel from the subscriptions screen when you no longer want to follow it.

Channels and cached feed entries are stored locally. Failed refreshes retain previously cached uploads and show the channel error. Up to 100 channels and 50 cached uploads per channel are retained. YouTube's public feed contains a limited recent selection, so this is not a complete channel archive and uploads can be missed between infrequent refreshes. Feeds refresh when the screen opens and on request; this version does not run scheduled feed checks.

## Watching a video

Tap an upload or its **Watch** action in **Feed**. The app resolves fresh media URLs and opens its own mpv player. Separate video and audio streams are supported. You can also paste an individual YouTube video link into the screen's video action, or use **Local media** to reach the original **Open URL** dialog. Sharing a YouTube video link to mpv YouTube also resolves it inside the app.

Supported video links include `/watch?v=…`, `youtu.be/…`, `/shorts/…`, `/live/…`, and `/embed/…`. Playlists are not imported. A Play selection replaces the current playback, including background playback. Resolved media URLs expire, so open the original video again when retrying later.

The player attaches only the validated media URLs and HTTP headers to that playlist entry; these values are restored when the file ends. Normal local-file and network-stream playback remain available through **Local media**.

## Saving MP4 or MP3

Choose **MP4** or **MP3**, then choose a filename and location in Android's system save picker. The selected destination can be Downloads or another document provider. The download runs through WorkManager with progress and cancellation available from **Downloads** and its notification.

- **MP4** downloads video and audio, merging them with FFmpeg. If necessary, FFmpeg converts the selected source to an MP4 container; the app checks the result before saving it.
- **MP3** extracts the audio and uses FFmpeg to encode it as MP3. It does not simply rename a different audio format.

Completed files are copied from the app's private temporary folder to the selected destination. Large videos and conversion need free storage and can take time. The app currently accepts finished videos for download; active, upcoming, and still-processing livestreams are rejected.

Use the YouTube tools update action when extraction stops working. It updates yt-dlp from its stable update channel. Public feeds and extraction may still fail when YouTube changes its site, limits requests, blocks the device or network, or requires sign-in. This fork does not provide cookies, account login, or access to private and age-restricted videos. An update cannot guarantee every video will work.

## Installing

The application ID is `is.xyz.mpv.ytdlp` and the launcher name is **mpv YouTube**, so it can be installed alongside upstream mpv-android. The default build requires Android 7.0 / API 24 or later. The existing `allstorage` flavor still requires Android 11 / API 30; its extra storage access is not needed for YouTube downloads through the save picker.

Use the APK for your device's ABI or the larger universal APK. Debug builds use a development signing key; future updates must use the same signing key to install over an existing build.

## Building the fork

Install Git, Python 3.9+, a compatible JDK (17 or later), and the Android SDK with platform 36, build-tools 36.0.0, and NDK 28.2.13676358. Set `ANDROID_HOME` to that SDK, or configure `sdk.dir` in an ignored `local.properties` file. For this app-only build, set `ndkVersion=28.2.13676358` in ignored `ndk.properties`; this NDK packages the prebuilt libraries. Native source builds use the NDK selected by upstream's build scripts.

Native mpv libraries are built separately from Gradle. For app-only development, the helper can reuse the official [2026-09-17 universal release](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17). It verifies the release tag's pinned commit, confirms the JNI sources and `MPVLib.kt` are unchanged, and checks the downloaded APK's SHA-256 before extracting only native `.so` files into ignored `app/src/main/libs` directories.

```sh
git fetch https://github.com/mpv-android/mpv-android.git tag 2026-09-17
python3 buildscripts/prepare-release-natives.py
./gradlew :app:testDefaultDebugUnitTest :app:assembleDefaultDebug :app:lintDefaultDebug
```

On Windows, use your Python executable and run the checked-in Gradle wrapper JAR directly because this repository does not include `gradlew.bat`:

```powershell
git fetch https://github.com/mpv-android/mpv-android.git tag 2026-09-17
python buildscripts/prepare-release-natives.py
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDefaultDebugUnitTest :app:assembleDefaultDebug :app:lintDefaultDebug
```

The helper accepts `--apk path/to/app-default-universal-release.apk` for an existing copy and `--verify-only` to check without extracting. It deliberately fails if the source tag is missing, the JNI interface changed, or the checksum differs. It does not delete native directories. Its pins are:

| Input | Pin |
| --- | --- |
| Upstream tag | `2026-09-17` |
| Upstream commit | `fdf74f6830c47dbaa8a22ac79726e8303f1db5af` |
| APK | `app-default-universal-release.apk` from that release |
| APK SHA-256 | `c3b505e45b919b767b9867e7f0d16fd823d77bc7c9a6e6b181b80ea60c018a70` |

For native changes, use the complete [upstream native build procedure](../buildscripts/README.md) on its supported Linux or macOS environment instead. The helper is for app-only changes and does not rebuild native code. The native-source workflow remains in [build.yml](../.github/workflows/build.yml); [youtube-feature.yml](../.github/workflows/youtube-feature.yml) tests, lints, and builds the app with the pinned release libraries.

APKs appear in `app/build/outputs/apk/default/debug/`. Gradle only packages ABIs containing both `libmpv.so` and `libplayer.so`, and refuses native packaging when none are available. Python and FFmpeg native executables are extracted at installation so the Android wrapper can run them.

## Dependencies and licensing

The original mpv-android [MIT license](../LICENSE) is retained. The APK also includes GPLv3 components through youtubedl-android; the top-level MIT license alone does not describe all bundled code. Redistributed builds need the applicable component licenses and corresponding source.

| Component | Version/source and license |
| --- | --- |
| mpv-android JNI/native release | [2026-09-17 source and dependency revisions](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17), [MIT license](https://github.com/mpv-android/mpv-android/blob/2026-09-17/LICENSE) |
| `io.github.junkfood02.youtubedl-android:library` and `:ffmpeg` | `0.18.1`; [exact source tag](https://github.com/yausername/youtubedl-android/tree/0.18.1), [GPLv3 license](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE), [FFmpeg build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md), [Python build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) |
| yt-dlp executable | Bundled by the Android wrapper and updateable in the app; [source and release history](https://github.com/yt-dlp/yt-dlp), [license](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE) |
| FFmpeg executable/libraries | Bundled by the wrapper and upstream mpv release; [source](https://ffmpeg.org/download.html), [component/build-dependent licensing](https://ffmpeg.org/legal.html) |
| AndroidX WorkManager | `work-runtime-ktx:2.10.5`; [release and source changes](https://developer.android.com/jetpack/androidx/releases/work#2.10.5) |
| AndroidX Lifecycle | `lifecycle-viewmodel-ktx:2.9.4`, `lifecycle-livedata-ktx:2.9.4`; [release and source changes](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.9.4) |
| Coil thumbnail loader | `io.coil-kt:coil:2.7.0`; [release](https://github.com/coil-kt/coil/releases/tag/2.7.0), [Apache 2.0 license](https://github.com/coil-kt/coil/blob/2.7.0/LICENSE.txt) |
| JUnit (test dependency) | `junit:junit:4.13.2`; [source](https://github.com/junit-team/junit4/tree/r4.13.2), [license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |

## Validation record

Checks recorded on 2026-10-16 with the default debug build and an Android API 36 x86_64 emulator. Playback, download, and 32 unit-test checks preceded the final UI-only removal of repeated headings and timestamps; no backend code changed in that cleanup. The resulting APK was assembled and linted, with phone and tablet layouts rechecked. Earlier export checks are identified separately.

| Check | Observed result |
| --- | --- |
| Build immediately before the final copy cleanup: `:app:testDefaultDebugUnitTest`, `:app:assembleDefaultDebug`, `:app:lintDefaultDebug` | Build succeeded; all 32 unit tests passed; lint reported zero errors and 93 warnings. |
| Final UI-only cleanup: assemble and lint | Build succeeded; zero lint errors and 93 warnings. Unit tests were not rerun for this cleanup. |
| Final phone layout | Visually checked at 1080 × 1920 pixels and 420 dpi. |
| Final tablet layout | Visually checked at 1600 × 1000 pixels and 200 dpi, with a three-column feed. |
| Narrow layout and large text, before copy cleanup | Visually checked at 320 dp width, 540 dpi, and `font_scale=1.5`; overflow menus and scrolling worked. |
| Add public `@YouTube` channel, initial APK | 15 uploads appeared in the local feed. |
| Real YouTube playback, initial APK | Native mpv initialized a 1080 × 1920 video stream and stereo audio. |
| MP4 download through the system save picker, initial APK | Saved a 9,558,746-byte file and reopened it successfully in mpv. |
| MP3 download through the system save picker, initial APK | Saved a 1,321,772-byte file. |
| Cold external `ACTION_SEND` YouTube link, before copy cleanup | Resolved the link inside the app and played 1080 × 1920 video with stereo audio. |
| Background player followed by a new `ACTION_VIEW`, before copy cleanup | After HOME and surface detachment, the existing player accepted a fresh video. Video output changed from `null` back to `gpu`; playback restarted at zero with audio ready and video playing. |
| Saved files reopened from Downloads, before copy cleanup | MP4 reopened successfully. MP3 was detected as MP3 and played with 48,000 Hz stereo audio. |
| New MP3 job and cancellation, before copy cleanup | Downloads displayed format, time, and progress; Cancel changed the job to Cancelled. |
| Cancellation follow-up | The cancelled worker's staging directory was empty. After the final UI cleanup, cancelling a feed refresh retained cached uploads. |
| Pinned native preparation helper | Release source, SHA-256, and 40 native library entries verified. |

Runtime and visual checks used the emulator; physical devices, older Android versions, and Android TV have not been verified. Live YouTube behavior depends on the selected video, network, and current site behavior, so these successful checks do not establish availability for every video or network.
