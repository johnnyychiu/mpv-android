# YouTube subscriptions, playback, and downloads

This fork adds an editable channel list to mpv-android, with six Hong Kong starter subscriptions. It uses public YouTube channel pages and RSS feeds for recent uploads, and yt-dlp for media resolution and downloads. It does not sign into Google, synchronize an account's subscriptions, or use the YouTube Data API, and requires no API key.

The launcher opens the YouTube screen directly. The logo and **Feed**, **Channels**, and **Downloads** navigation share one row. **Feed** shows recent uploads with thumbnails, **Channels** manages followed channels, and **Downloads** shows the durable saved-file library and active jobs. **Playlists** organizes saved MP4 and MP3 files. Channel and video-link actions remain available; the overflow menu opens **Local media** and the yt-dlp update action.

The interface uses flat neutral surfaces, square corners, and no outlines, following the requested visual direction. Thumbnails and titles take priority, with compact navigation and clear playback and download actions. The emphasis on content and simple navigation draws on [YouTube's design principles](https://blog.youtube/inside-youtube/design-principles-use-put-creators-center-stage/) and [Netflix's mobile design](https://about.netflix.com/en/news/introducing-exciting-new-ways-to-find-and-enjoy-your-next-favorite-on-mobile); the strict square treatment is specific to this fork.

Feed and list content starts directly beneath the navigation row, with tighter spacing between items and actions. Persistent section headings, last-refresh timestamps, repeated tab titles, and subtitles are omitted to keep interface copy minimal.

## Following channels

The `2026-10-16-youtube.2` update adds these starter subscriptions once on fresh and existing installations. Existing manual subscriptions are preserved. You can add or remove channels freely; removed starters do not return on later launches. The starter channel identities were verified on 2026-10-16.

| Starter channel | Official channel link |
| --- | --- |
| Pomato 小薯茄 | [@pomatohk](https://youtube.com/@pomatohk) |
| Mill MILK | [@mm.millmilk](https://youtube.com/@mm.millmilk) |
| FHProductionHK | [@FHProductionHK](https://youtube.com/@FHProductionHK) |
| 毛記電視 | [@100MOSTHongKong](https://youtube.com/@100MOSTHongKong) |
| 米紙 | [@ricezi2hk](https://youtube.com/@ricezi2hk) |
| 笑波子 | [@smilingboris1118](https://youtube.com/@smilingboris1118) |

1. Open the app and choose **Channels**.
2. Add a channel link, such as `https://www.youtube.com/@handle` or `https://www.youtube.com/channel/UC…`. Legacy `/c/` and `/user/` channel links are also accepted.
3. Refresh to fetch the latest uploads for your saved channels. Remove a channel from the subscriptions screen when you no longer want to follow it.

Channels and cached feed entries are stored locally. During refresh, completed channels appear progressively, so you can browse their uploads while the remaining channels load. Failed refreshes retain previously cached uploads and show the channel error. Up to 100 channels and 50 cached uploads per channel are retained. YouTube's public feed contains a limited recent selection, so this is not a complete channel archive and uploads can be missed between infrequent refreshes. Feeds refresh when the screen opens and on request; this version does not run scheduled feed checks.

## Watching a video

Tap an upload or its **Watch** action in **Feed**. The app resolves fresh media URLs and opens its own native mpv player; playback does not use a WebView. Separate video and audio streams are supported. You can also paste an individual YouTube video link into the screen's video action, or use **Local media** to reach the original **Open URL** dialog. Sharing a YouTube video link to mpv YouTube also resolves it inside the app.

Supported video links include `/watch?v=…`, `youtu.be/…`, `/shorts/…`, `/live/…`, and `/embed/…`. YouTube playlists are not imported. A Play selection replaces the current playback, including background playback. Resolved media URLs expire, so open the original video again when retrying later.

The player attaches only the validated media URLs, HTTP headers, and bounded request-size hints from yt-dlp to that playlist entry; these values are restored when the file ends. Request-size hints are passed to mpv's native curl and FFmpeg HTTP backends. Normal local-file and network-stream playback remain available through **Local media**.

If the default extraction cannot provide a usable stream, the app can retry with yt-dlp's supported Android client and a combined H.264/AAC MP4 stream. This fallback can be limited to 360p; it does not guarantee a particular quality or availability for every video. Playback still runs entirely in native mpv.

## Saving MP4 or MP3

Choose **MP4** or **MP3**, then choose a filename and location in Android's system save picker. The selected destination can be Downloads or another document provider. The download runs through WorkManager with progress and cancellation available from **Downloads** and its notification.

- **MP4** downloads video and audio, merging them with FFmpeg. If necessary, FFmpeg converts the selected source to an MP4 container; the app checks the result before saving it.
- **MP3** extracts the audio and uses FFmpeg to encode it as MP3. It does not simply rename a different audio format.

Completed files are copied from the app's private temporary folder to the selected destination. Large videos and conversion need free storage and can take time. The app currently accepts finished videos for download; active, upcoming, and still-processing livestreams are rejected.

When metadata lookup needs the Android-client fallback, the actual download request uses that same client selection. Successful downloads enter the durable library only after their destination file has been copied and closed.

Use the YouTube tools update action when extraction stops working. It updates yt-dlp from its stable update channel. Public feeds and extraction may still fail when YouTube changes its site, limits requests, blocks the device or network, or requires sign-in. This fork does not provide cookies, account login, or access to private and age-restricted videos. An update cannot guarantee every video will work.

## Offline playlists

Version `2026-10-16-youtube.3` (version code 50) keeps completed downloads in an app-private library independently of WorkManager's job-history retention. Download records and named, ordered playlists are saved together using `AtomicFile` and a shared read-modify-write lock. Existing completed jobs still present in WorkManager are imported idempotently; repeated imports of the same document preserve its playlist references.

1. Open **Playlists** and choose **New playlist** to name a list.
2. Use **Add saved files**, or a saved download's **Add to playlist** action, to select files from the library.
3. Use **Move up**, **Move down**, or **Remove from playlist** to change the order and contents. **Rename** and **Delete playlist** change playlist metadata only.
4. Choose **Play all** to start the ordered queue in native mpv. It replaces current playback, including background playback. The player's previous/next, shuffle, and repeat controls remain available.

**Import MP4 / MP3 files** adds previously saved files through Android's document picker, including files whose old WorkManager records have already been pruned. Imports request lasting read access and accept up to 50 files at a time. The library supports up to 1,000 saved files and 50 playlists, with 200 entries per playlist. Playlist actions never delete downloaded files.

Offline playback reads the saved documents directly and does not call yt-dlp or the YouTube API. The player checks file access before replacing the current queue; unavailable documents produce an error so you can restore access or import them again. Files need to remain available from their selected document provider for playback without a network connection.

## Installing

The application ID is `is.xyz.mpv.ytdlp` and the launcher name is **mpv YouTube**, so it can be installed alongside upstream mpv-android. The default build requires Android 7.0 / API 24 or later. The existing `allstorage` flavor still requires Android 11 / API 30; its extra storage access is not needed for YouTube downloads through the save picker.

Version `2026-10-16-youtube.3` includes verified yt-dlp `2026.08.19` for fresh installations; using the update action is not required to install that runtime. Confirmed newer versions installed through the updater are preserved.

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

Gradle's `prepareBundledYtDlp` task downloads the official portable extractor into ignored `app/build/generated/ytDlpAssets/yt-dlp/yt-dlp`. APK asset merging and lint depend on this task; pure unit-test compilation does not fetch the runtime. The task checks both size and SHA-256 before atomically replacing the generated asset. A previously verified asset is reused, including with `--offline` when the other build dependencies are already cached. Missing or invalid assets fail an offline APK build. No extractor binary is committed to the repository.

| Extractor input | Pin |
| --- | --- |
| Version | `2026.08.19` |
| Official executable | [Portable yt-dlp release asset](https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp) |
| Size | 3,072,469 bytes |
| SHA-256 | `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6` |
| Source | [Exact `2026.08.19` tag](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19) |

After the Android wrapper initializes, `BundledYouTubeTools` verifies and installs the asset into its private runtime directory using `AtomicFile`, then commits an installation marker. Matching pinned binaries and confirmed newer stable/nightly updater versions are retained. A fingerprint check detects the wrapper's old raw binary even when restored updater preferences claim a newer version, preventing stale metadata from skipping the required installation.

For native changes, use the complete [upstream native build procedure](../buildscripts/README.md) on its supported Linux or macOS environment instead. The helper is for app-only changes and does not rebuild native code. The native-source workflow remains in [build.yml](../.github/workflows/build.yml); [youtube-feature.yml](../.github/workflows/youtube-feature.yml) tests, lints, and builds the app with the pinned release libraries.

APKs appear in `app/build/outputs/apk/default/debug/`. Gradle only packages ABIs containing both `libmpv.so` and `libplayer.so`, and refuses native packaging when none are available. Python and FFmpeg native executables are extracted at installation so the Android wrapper can run them.

## Dependencies and licensing

The original mpv-android [MIT license](../LICENSE) is retained. The APK also includes GPLv3 components through youtubedl-android; the top-level MIT license alone does not describe all bundled code. Redistributed builds need the applicable component licenses and corresponding source.

| Component | Version/source and license |
| --- | --- |
| mpv-android JNI/native release | [2026-09-17 source and dependency revisions](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17), [MIT license](https://github.com/mpv-android/mpv-android/blob/2026-09-17/LICENSE) |
| `io.github.junkfood02.youtubedl-android:library` and `:ffmpeg` | `0.18.1`; [exact source tag](https://github.com/yausername/youtubedl-android/tree/0.18.1), [GPLv3 license](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE), [FFmpeg build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md), [Python build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) |
| yt-dlp executable | Verified portable `2026.08.19` asset supplied by this fork, updateable in the app; [exact source tag](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19), [license at that tag](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/LICENSE) |
| FFmpeg executable/libraries | Bundled by the wrapper and upstream mpv release; [source](https://ffmpeg.org/download.html), [component/build-dependent licensing](https://ffmpeg.org/legal.html) |
| AndroidX WorkManager | `work-runtime-ktx:2.10.5`; [release and source changes](https://developer.android.com/jetpack/androidx/releases/work#2.10.5) |
| AndroidX Lifecycle | `lifecycle-viewmodel-ktx:2.9.4`, `lifecycle-livedata-ktx:2.9.4`; [release and source changes](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.9.4) |
| Coil thumbnail loader | `io.coil-kt:coil:2.7.0`; [release](https://github.com/coil-kt/coil/releases/tag/2.7.0), [Apache 2.0 license](https://github.com/coil-kt/coil/blob/2.7.0/LICENSE.txt) |
| JUnit (test dependency) | `junit:junit:4.13.2`; [source](https://github.com/junit-team/junit4/tree/r4.13.2), [license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |

## Validation record

Checks for `2026-10-16-youtube.3` (version code 50) were recorded on 2026-10-16 with the default debug build and an Android API 36 x86_64 emulator. Native public YouTube playback, fresh-install runtime preparation, and new MP4/MP3 downloads passed. Checkpoints from before the final bundled-installer integration are identified separately. The fresh-install test cleared app data only on the disposable emulator.

| Version 50 check | Observed result |
| --- | --- |
| Final unit tests, assemble, and lint | APK assembled; all 72 unit tests passed, including seven bundled-runtime policy tests and five fallback tests; lint reported zero errors and 109 warnings. |
| Native Pomato playback | Video `_gDMltq-VoI` played through the actual app's default extraction followed by the supported Android-client fallback: 640 × 360 H.264 video and 44,100 Hz stereo AAC audio. Its Chinese/emoji title worked. Native request-size settings were `curl-max-request-size=10485760` and `stream-lavf-o=request_size=10485760`. |
| Pomato deep seek | Seeking to approximately 32 minutes into the 45-minute video produced current video frames and a playing media-session state. |
| Fresh installation, without using Update | Video `8Pae6Wy1abE` played as 360 × 640 H.264 with 48,000 Hz stereo audio. The runtime matched the pinned `2026.08.19` SHA-256 above, and the installation marker was present. |
| Fresh MP4 export | The system save picker and real download worker saved a 3,090,979-byte MP4. |
| Fresh MP3 export | The system save picker and real download worker saved a 1,302,329-byte MP3. |
| Download fallback and durable registration | The successful metadata client's selection reached both real download requests. Both jobs added durable records, giving two catalog entries before their successful worker results. |
| Upgrade preservation | The existing two saved files and one playlist remained after upgrade, before the separate fresh-install reset. |

The following offline checks used an earlier version-50 build before the final bundled-installer integration; the offline implementation was unchanged by that integration. That checkpoint passed 60 unit tests and lint with zero errors and 109 warnings.

| Earlier version 50 offline check | Observed result |
| --- | --- |
| Import and playlist editing | Imported two real MP4/MP3 files saved by the earlier build, created **Offline Mix**, moved MP4 first, and verified the order persisted. |
| Native playlist sequence | mpv played both imported files in order. |
| Airplane mode enabled | MP4 played at 1080 × 1920 with 44,100 Hz stereo audio, followed by MP3 at 48,000 Hz stereo. |
| Previous-track control | `MEDIA_PREVIOUS` returned from MP3 to the MP4 entry. |
| Force-stop and cold restart | The same saved playlist and persisted document grants worked offline after restart. |
| Background queue replacement | After HOME, an explicit offline-playlist selection reached the existing Activity, replaced the queue, and restored GPU output at 1080 × 1920 with stereo audio. |

Earlier checks for `2026-10-16-youtube.2` (version code 49) were recorded on 2026-10-16 with the default debug build and an Android API 36 x86_64 emulator. Live playback was not verified in that build: bundled-extractor requests returned HTTP 403, including format checks, and a newer-extractor probe encountered YouTube's bot/sign-in and rate-limit responses.

| Follow-up check | Observed result |
| --- | --- |
| `:app:testDefaultDebugUnitTest`, `:app:assembleDefaultDebug`, `:app:lintDefaultDebug` | Build succeeded; all 43 unit tests passed; lint reported zero errors and 93 warnings. |
| Fresh installation | All six starter channels loaded, producing 90 videos with no channel errors. |
| Upgrade installation | Existing manual subscriptions and cached videos were preserved while the six starters were added. |
| Remove and re-add a starter | The removed starter stayed removed after restart and could be added manually again. |
| Progressive refresh | 60 videos were already visible after four of six channels completed. |
| Feed ordering | The first six cards represented all six channels in round-robin order. |
| Compact phone navigation | Visually checked at 320 dp width and 540 dpi. At `font_scale=1.5`, the single-row navigation scrolled horizontally. |
| Tablet layout | Visually checked at 1600 × 1000 pixels and 200 dpi. |
| Chinese and emoji playback titles | Fixed the native option byte count; 11 playback-policy tests cover framing and validation. The former option-framing failure no longer occurred at runtime. |
| Version 49 live playback | Not verified: the framing fix reached media loading, but bundled-extractor requests returned HTTP 403. Earlier successful playback and exports below belong to the previous build. |
| Optional extractor update probe | For Pomato video `_gDMltq-VoI`, official yt-dlp `2026.08.19` reached a bot/sign-in notice through the app's Watch action; the command-line probe returned HTTP 429 on the same network. This did not verify playback or establish that an extractor update resolves the failure. |
| Version 49 APK extractor | This older APK used wrapper `0.18.1`'s yt-dlp `2025.11.12` resource. The probe did not lead to a bundled-extractor update in version 49. |

Previous `2026-09-17-release-youtube` build checks were recorded on 2026-10-16 with the default debug build and an Android API 36 x86_64 emulator. Playback, download, and 32 unit-test checks preceded that build's final UI-only removal of repeated headings and timestamps; no backend code changed in that cleanup. The resulting APK was assembled and linted, with phone and tablet layouts rechecked. Earlier export checks are identified separately.

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
