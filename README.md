# FlowLite

Free voice dictation that runs on your own device. Speak, and the text lands in the field you are typing in.

- **Private.** Speech recognition runs on your phone or computer with NVIDIA's Parakeet model. Audio never leaves your device.
- **Free.** No account, no subscription, no word limits, no telemetry.
- **Optional sync.** Keep history, your dictionary and settings the same across devices with a sync server you run yourself.

## Download

Get the latest version from the **[Releases page](https://github.com/mchristo839/flowlite/releases/latest)**.

| Platform | File | How to install |
|---|---|---|
| Android 8 or later (64-bit) | `flowlite-X.Y.Z.apk` | Open the link on your phone, download the APK and open it. Allow "Install unknown apps" for your browser when Android asks. |
| Linux (Debian, Ubuntu, Mint) | `FlowLite_X.Y.Z_amd64.deb` | `sudo apt install ./FlowLite_*_amd64.deb` |
| Windows 10 and 11 (64-bit) | `FlowLite_X.Y.Z_x64-setup.exe` | Run it. If SmartScreen warns you, click **More info**, then **Run anyway**. |
| Mac with Apple Silicon (M1 or newer) | `FlowLite_X.Y.Z_aarch64.dmg` | Open it and drag FlowLite to Applications. See [Mac](#mac) for the first launch. |
| Mac with an Intel processor | `FlowLite_X.Y.Z_x64.dmg` | As above. To check which Mac you have, open the Apple menu, About This Mac. "Chip: Apple M..." means Apple Silicon. |
| iPhone | | Planned |

Installed apps check this page every few hours and offer new versions. Android installs an update only when it carries the same signing key as the installed app. Linux, Windows and Mac install only releases signed with the FlowLite release key. `SHA256SUMS` on each release lists the file checksums.

## Android

Installing:

1. Open the [latest release](https://github.com/mchristo839/flowlite/releases/latest) on your phone and download the `.apk`.
2. Open it. Allow "Install unknown apps" for your browser when Android asks.
3. If Google Play Protect warns about an unknown app, tap **More details, Install anyway**. FlowLite is not on the Play Store, so Play Protect has not scanned it before.

On first launch a setup screen walks through each permission.

**Accessibility greyed out?** Android 13 and newer block it for apps installed from a download. Open Settings, Apps, FlowLite, tap the three-dot menu at the top right and choose **Allow restricted settings**, then turn Accessibility on. If the menu is missing, try turning on FlowLite under Accessibility once, close the "Restricted setting" message, then look again. The setup screen has a button for this.

The permissions:

| Permission | Why |
|---|---|
| Microphone | Records your voice. Audio stays on the phone. |
| Display over other apps | Shows the floating dictation bubble. |
| Accessibility | Finds the focused text field and inserts the text. Also restarts the bubble after a reboot. |
| Battery: Unrestricted | Some phones, Samsung in particular, stop background apps otherwise. |
| Notifications | The always-on notification that keeps the bubble alive, with a "Paste last" button. |

Then download a speech model, ideally on Wi-Fi: Parakeet (671 MB, best accuracy, 25 European languages) or Whisper base (160 MB). Files are checked against pinned SHA-256 hashes.

Using it:

- Tap a text field and the bubble appears. Tap it to start, then tap the tick to insert the text or the X to discard it. You can also hold the bubble and release.
- Drag the bubble anywhere. Drag it to the bottom centre to hide it for 10 minutes. It never shows in password fields.
- If an app refuses the text, it goes to the clipboard. "Paste last" in the notification tries again.
- Voice keyboard: Settings, "Turn on the FlowLite voice keyboard", for apps where the bubble cannot insert text.
- Private fields: dictation into a password field is never saved to history, synced or sent for AI rewrite. The voice keyboard also treats private browser tabs this way. The bubble cannot tell a private tab apart, so use the voice keyboard there.
- Say "scratch that" to drop the last sentence. Say "comma", "period", "new line" or "new paragraph" for punctuation.
- Dictionary: add words FlowLite should spell your way, or replacements such as "my email" for your address.
- AI rewrite (off by default) sends text to any OpenAI-compatible service you configure with your own API key.

## Linux, Windows and Mac

Push-to-talk desktop dictation, built from a fork of [Handy](https://github.com/cjpais/Handy). The code lives in the [`sync` branch of the fork](https://github.com/mchristo839/Handy/tree/sync). FlowLite's additions are in `src-tauri/src/flowlite_sync/` and `src/components/flowlite/`.

### Windows

FlowLite is not signed with a paid Microsoft certificate, so SmartScreen shows "Windows protected your PC" the first time. Click **More info**, then **Run anyway**.

### Mac

FlowLite is not notarised by Apple, so macOS blocks the first launch. Open FlowLite once, close the warning, then open System Settings, Privacy & Security, scroll down and click **Open Anyway**. macOS then asks for microphone and Accessibility access. Accessibility lets FlowLite type into other apps.

After an update macOS sometimes asks for Accessibility again. If dictation stops typing, open System Settings, Privacy & Security, Accessibility, remove FlowLite with the minus button and add it again.

## Sync between devices (optional)

FlowLite works fully offline. To share history and your dictionary between devices, run your own server: see **[docs/self-host.md](docs/self-host.md)**. It takes about 15 minutes on a small VPS. Then enter its address in the app under Sync.

The server stores text only. Transcripts are not end-to-end encrypted, so whoever controls the server can read them. That is why each person runs their own.

## Repository layout

| Folder | Contents |
|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose, sherpa-onnx) |
| `server/` | Self-hostable sync server (PocketBase, Docker Compose, automatic HTTPS) |
| `docs/` | Self-hosting guide and sync API contract |
| `branding/` | App icon |
| `.github/workflows/release.yml` | Builds and publishes releases |

## Building

Android needs JDK 21 and the Android SDK (platform 36, build-tools 35):

```
cd android
./gradlew assembleDebug
```

For Linux, follow `BUILD.md` in the [Handy fork](https://github.com/mchristo839/Handy/tree/sync), then build with `bun run tauri build --bundles deb --config src-tauri/tauri.flowlite.conf.json`.

## Publishing a release (maintainer)

Push a version tag. GitHub builds both apps, signs them and publishes them on the Releases page:

```
git tag v0.2.0 && git push origin v0.2.0
```

To test a change without publishing, open Actions, Release, "Run workflow".

## Credits and licences

- Linux app: fork of [Handy](https://github.com/cjpais/Handy) by CJ Pais, MIT licence
- Speech engine on Android: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache 2.0
- Speech models: [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) (CC BY 4.0), OpenAI Whisper (MIT), Silero VAD (MIT)
- Sync server: [PocketBase](https://pocketbase.io), MIT

FlowLite itself is released under the MIT licence, see [LICENSE](LICENSE).
