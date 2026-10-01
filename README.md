# RAINAX YT DOWNLOADER

Premium Android video and music downloader. Powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg
through [youtubedl-android](https://github.com/yausername/youtubedl-android).

## Features
- Browse **YouTube** inside the app and download with the floating Download button on any video
- Any other site (TikTok, Instagram, Facebook, X, Vimeo and 1000+ more) through **Share** or a pasted link
- Music (MP3 / M4A), video up to the best available quality, subtitles (`.srt`), full playlists (up to 1000)
- Background downloads with a foreground service: keeps going when the app is closed, auto-resume and retries
- Queue with 1 to 4 parallel downloads, pause / resume / cancel, select several, Pause all / Resume all / Cancel all
- One live progress bar per download and per notification
- YouTube sign-in inside the app for private, members-only and age-restricted videos
- Light, dark or system theme, optional ad blocking, Wi-Fi only mode
- Choose your own download folder (default: `Downloads/rainax-yt-downloader`)
- The download engine (yt-dlp) updates itself every time the app opens (and has an Update now button)

## Build
Push to `main`: the APK is in **Actions > Build RAINAX APK > Artifacts**.
Push a tag such as `v7.1.4` to publish a **GitHub Release** with the APK and its SHA-256 checksum.

```
git tag v7.1.4 && git push origin v7.1.4
```

Requires Android 10 (API 29) or newer.

## Signing key
Every build is signed with the same key, so new versions install over old ones.

- Out of the box the key in `app/rainax-release.keystore` is used.
- For a private key, run `bash scripts/make-private-key.sh` once (Termux or Linux, needs `keytool` and `gh`).
  It stores the key in GitHub Secrets and the workflow uses it automatically. **Keep the generated file safe:
  if you lose it, updates cannot install over the old app.**

## About the Play Protect message
Android shows "Play Protect: app from an unknown developer" for every app installed outside the Play Store that
Google has not scanned before. This is not a virus warning and it is not caused by a bug in the app.
Play Protect lets a few well-known apps through, but it cannot be switched off from inside an app.
Options that work:
1. On the warning tap **More details > Install anyway** (once per install or update).
2. Or open Play Store > profile > Play Protect > settings and turn off **Scan apps with Play Protect**
   while installing.
3. Or use *Install via* the Files app / browser that you trusted for "Install unknown apps".

The app is built as a normal (non-debuggable) release, signed with a stable key and with no unusual permissions,
which is the most an app can do. A YouTube downloader cannot be published on the Play Store (it breaks YouTube's terms).

## Legal
Only download content you have the right to download and respect each site's terms of service.
Not affiliated with YouTube or Google.

## License
GPL-3.0, matching the licenses of the libraries used.
