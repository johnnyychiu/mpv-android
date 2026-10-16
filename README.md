# mpv YouTube for Android

This fork adds a local YouTube subscriptions list, direct playback in mpv, and MP4 or MP3 downloads powered by yt-dlp and FFmpeg. Add channel links manually and refresh their public feeds; no Google account, Google Data API key, or subscription import is required.

The fork installs as **mpv YouTube** (`is.xyz.mpv.ytdlp`) alongside the original app and requires Android 7.0 or later. See the [YouTube guide](docs/YOUTUBE.md) for usage, limitations, dependency licenses, and the app-only build procedure. The store links below point to the original mpv-android app, which does not include these additions.

[![Build Status](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml)

mpv-android is a video player for Android based on [libmpv](https://github.com/mpv-player/mpv).

## Features

* Hardware and software video decoding
* Gesture-based seeking, volume/brightness control and more
* libass support for styled subtitles
* Secondary (or dual) subtitle support
* High-quality rendering with advanced settings (scalers, debanding, interpolation, ...)
* Play network streams with the "Open URL" function
* Background playback, Picture-in-Picture, keyboard input supported

### Library?

mpv-android is **not** a library/module (AAR) you can import into your app.

If you'd like to use libmpv in your app you can use our code as inspiration.
The important parts are [`MPVLib`](app/src/main/java/is/xyz/mpv/MPVLib.kt), [`BaseMPVView`](app/src/main/java/is/xyz/mpv/BaseMPVView.kt) and the [native code](app/src/main/jni/).
Native code is built by [these scripts](buildscripts/).

## Downloads

You can download mpv-android from the [Releases section](https://github.com/mpv-android/mpv-android/releases) or

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=is.xyz.mpv)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/is.xyz.mpv)

**Note**: Android TV is supported, but only available on F-Droid or by installing the APK manually.

## Building from source

For the fork's app-only changes, the [YouTube build guide](docs/YOUTUBE.md#building-the-fork) includes a checksum-verified native-library preparation script and Windows instructions. The [YouTube feature workflow](.github/workflows/youtube-feature.yml) runs unit tests and produces debug APKs with the pinned upstream native libraries.

Take a look at the [README](buildscripts/README.md) inside the `buildscripts` directory.

Some other documentation can be found at this [link](http://mpv-android.github.io/mpv-android/).
