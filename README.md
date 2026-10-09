# RAINAX Tube

A fast Android app to watch and download videos and music. It reads YouTube with
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) and downloads with its own downloader, so it needs no yt-dlp or FFmpeg.

Requires Android 10 (API 29) or newer.

## Features
- **YouTube built in:** home feed, Music, search (videos, channels, playlists), channels and playlists
- **Player:** quality choice (Auto adapts to your network), playback speed, fullscreen, mini player,
  and background play with lock-screen controls
- **Watching extras:** Shorts feed (swipe up), Continue watching, chapters on the seek bar, SponsorBlock skipping
  and Return YouTube Dislike counts
- **YouTube account (optional):** sign in to see your feed and subscriptions, subscribe, like, Watch later,
  comment, and like or reply to comments (Top / Newest)
- **Downloads:** video up to 8K when the video has it, audio as M4A, WebM or MP3, subtitles (`.srt`), and whole playlists
- **Social tab:** TikTok, Instagram, Facebook and more, with simple how-to steps; copied links are offered for download
- **Pick several:** the + on each video selects it, then one tap downloads them all
- **Library:** your downloaded music and videos with search, playlists, Play all, Shuffle and Repeat; music files
  get their title, artist and cover picture
- **Download manager:** 1 to 4 downloads at once, pause, resume, auto-retry, Wi-Fi only mode, and a custom download folder
- **Themes:** System, Light, Dark and AMOLED black
- **In-app updates** from this repository's GitHub Releases (they download in the Downloads tab, with pause and resume)
- **No ads**

## Build
Every push to `main` builds the APK (**Actions > Build RAINAX APK > Artifacts**).
Pushing a tag publishes a **GitHub Release** with the APK and its SHA-256 checksum. `whatsnew.txt` becomes the update notes shown in the app.

```
git tag v8.9.28 && git push origin v8.9.28
```

The MP3 encoder is native code (`app/src/main/cpp`). GitHub's build machines build it with the NDK and CMake they already have installed.

## Signing key
Releases are signed with a private key kept only in GitHub Secrets. It is never stored in the repository.
Create the key once with `bash scripts/make-private-key.sh` (Termux or Linux; needs `keytool` and `gh`).
**Back up the generated keystore and password.** If you lose them, new versions cannot install over the old app.

## Installing (Play Protect message)
Android warns about every app installed from outside the Play Store that Google has not scanned before ("app from an unknown developer").
This is not a virus warning. To install, tap **More details > Install anyway**.
YouTube downloaders are not allowed on the Play Store.

## Official app and name
- The only official RAINAX is published at **https://github.com/raowaheedbhkpk-dev/rainax-yt-downloader/releases**,
  signed with the owner's private key. The app checks its own signature at start: a copy that someone changed and
  signed again shows "Not the official RAINAX" and points to the download page.
- The code is open source (GPL-3.0), but the **name "RAINAX", "RAINAX Tube" and the RAINAX logo are not part of that
  licence**. Forks and modified versions must use a different name and logo, a different app ID
  (not `com.rainax.ytdownloader`), and must not present themselves as RAINAX or as made by its author.

## Legal
Only download content you have the right to download, and respect each site's terms of service.
Not affiliated with YouTube, Google, TikTok or any other site.

## License and third-party code
RAINAX is released under GPL-3.0, as required by NewPipe Extractor.

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor): GPL-3.0
- [LAME](https://lame.sourceforge.io/) 3.99.5 (MP3 encoder, `app/src/main/cpp/lame`): LGPL-2.0-or-later, see `app/src/main/cpp/lame/COPYING`
- AndroidX, Media3 (ExoPlayer) and Material Components: Apache-2.0
- [yt-dlp-ejs](https://github.com/yt-dlp/ejs) 0.8.0 (YouTube player-code solver, `app/src/main/assets/ejs`): Unlicense; it bundles meriyah (ISC) and astring (MIT), whose licences are at the top of `lib.min.js`
- Fonts [Bricolage Grotesque](https://fonts.google.com/specimen/Bricolage+Grotesque) and [Figtree](https://fonts.google.com/specimen/Figtree) (`app/src/main/res/font`): SIL Open Font License 1.1
