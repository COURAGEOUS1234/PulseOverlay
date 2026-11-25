# Pulse Overlay

**Download:** [PulseOverlay-debug.apk](https://github.com/COURAGEOUS1234/PulseOverlay/releases/download/latest/PulseOverlay-debug.apk), the latest test build, no sign-in needed.

Android music visualiser that reacts to audio from any app (Spotify, YouTube, local players, games) and draws on top of everything. Touches pass through the overlay.

## Build
1. Open the folder in Android Studio (Ladybug or newer). Gradle sync pulls AGP 8.7.3 / Kotlin 2.0.21.
2. Run on a device with Android 10+. Emulators usually give no audio to capture, so use a real phone.
3. CLI: `./gradlew installDebug`

No library dependencies: framework APIs only.

## First run
Start → allow **Display over other apps** → back, Start → allow **microphone** (needed to read the output signal, the mic itself isn't used) → accept the **"start recording/casting"** prompt (audio only, no screen frames are captured). The app goes to the background. Play music anywhere.
Stop from the notification or the app.

## Themes and colours (v1.1)
- **Liquid glass:** 20 translucent pill bars with tint, sheen, rim and highlight. In Playback capture mode on Android 12+, with the *entire screen* shared, they blur the content behind each bar. That uses a ¼-res live screen copy held in memory and never saved. Elsewhere they fall back to frosted glass.
- **Morphing matter:** gooey metaballs (blur + alpha threshold) flowing along the bottom. A pale rim and a saturated core give it depth, drifting pores morph with the treble, and it swells and speeds up on detected beats. Needs Android 12+; older versions get plain blobs.
- **Album-art colours:** reads the playing app's media session (needs **notification access**) and extracts a 4-stop vivid palette that glides between songs. Sideloaded on Android 13+: if the toggle is greyed out, go to Settings → Apps → Pulse Overlay → ⋮ → **Allow restricted settings**.
- Debug builds are now signed with a fixed key (`app/debug.keystore`), so new builds install over old ones.

## Architecture
| File | Role |
|---|---|
| `VisualizerService` | Foreground service (`mediaProjection` / `microphone` type); owns the overlay window + audio source |
| `AudioSources.kt` | `PlaybackCaptureSource`: AudioPlaybackCapture → 44.1 kHz PCM → 2048-pt FFT, 512 hop (~86 fps). `GlobalVisualizerSource`: legacy `Visualizer(0)` fallback |
| `Fft.kt` | Radix-2 FFT, Hann window, allocation-free |
| `SpectrumProcessor` | 64 log-spaced bands 40 Hz–16 kHz, +3 dB/oct tilt, auto-gain (fast attack, slow release), time-based smoothing |
| `OverlayView` | Vsync-driven Canvas: bars, top+bottom bars, waveform, ring; hue drift |
| `ScreenBackdrop` | MediaProjection → VirtualDisplay → ImageReader, ¼ res, ~30 fps, triple-buffered |
| `AlbumArt.kt` | Notification-listener stub, media-session watcher, hue-bucket palette extraction |
| `MainActivity` | Settings (live-updating via SharedPreferences listener) + permission flow |

## Known limits
- **Apps can opt out of capture** (`allowAudioPlaybackCapture="false"`, or they flag their audio as non-capturable). In that case Playback capture gets silence, so switch to Global Visualizer. Calls are never capturable.
- **Global Visualizer (session 0)** is deprecated. Some OEM ROMs return zeros or throw. It runs at about 20 Hz, and the renderer eases between frames.
- **Opacity is capped at 0.8.** Above that, Android 12+ blocks touches from passing through the overlay.
- The capture consent prompt appears every session. Android doesn't allow persisting it.
- Bluetooth latency: the visual tracks what's sent to the mixer, so with BT headphones it runs ~100–250 ms *ahead* of what you hear.
- Continuous full-screen redraw costs battery. Stop it when you're not using it.

## Next steps
- Quick Settings tile for one-tap start/stop
- Beat detection (low-band energy flux) for pulse/flash effects
- Edge-glow style that hugs the screen corners

## CI
Every push runs `.github/workflows/build.yml`. To download the APK, open the Actions tab, then the latest run, then **PulseOverlay-debug**.
