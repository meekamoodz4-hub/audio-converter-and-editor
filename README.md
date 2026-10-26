# Audio Converter and Editor

An Android audio utility app with a dark purple and electric-blue interface.

## Features
- Extract audio from video and convert audio files to MP3, WAV, M4A/AAC, or FLAC where the input codecs are supported.
- Crop audio using start and end times in seconds.
- Compress supported output formats with bitrate options.
- Play the last exported file.
- Save exports to Downloads/Audio Converter and Editor.
- Google AdMob banner, interstitial, rewarded, and app-open ad placements.
- In-app Privacy Policy button.
- GitHub Actions builds a debug APK on every push to main.

## Build
Open the Actions tab in GitHub and select Build Audio Converter and Editor APK. When the run succeeds, open the run and download the Audio-Converter-and-Editor-debug-apk artifact.

## Notes
- Android 7.0 (API 24) or newer is required.
- AdMob may not return live ads until the app and ad units are ready in AdMob. Do not click your own ads. Use Google test ads while actively developing and testing.
- Export is gated behind a rewarded ad: if an ad cannot be loaded or completed, the file is not saved.
- Audio codec support depends on the selected input, output format, and FFmpeg build.
