# p3view for Android

Minimal Android viewer for the ThermalMaster P3 (and P1) USB thermal camera.
Open the app, plug in the camera, see the thermal image. Target: Pixel 10 on Android 17
(minSdk 36, targetSdk 37).

Plain Kotlin against the platform SDK: no Gradle, no AndroidX, no native code.

## Signing key

Android only installs an update over an existing app if both APKs are signed with the same
key, so the repo's builds are signed with a key you create once. It is not in the repo
(`debug.keystore` is git-ignored); create it in the repo root:

```sh
nix develop -c ./genkey.sh    # writes debug.keystore, password "android"
```

Keep that file. If you lose it, or generate a new one, uninstall the app from the phone
before installing the next build (its saved settings go with it).

## Build

```sh
nix build            # result/p3view-unsigned.apk
nix run .#sign       # signs it with ./debug.keystore into build/p3view.apk
```

The Nix build cannot see the git-ignored key, so `nix build` makes an unsigned APK and
`nix run .#sign` signs it. Run `nix run` from the repo root.

The flake accepts the Android SDK license and pulls platform 37.0 and build-tools 37.0.0
through nixpkgs `androidenv`. These are unfree, so they come from Google's servers rather
than the NixOS binary cache.

Without Nix: `nix develop` gives a shell with everything set, or export `ANDROID_JAR`,
`BUILD_TOOLS` and `KOTLIN_STDLIB` yourself and run `./build.sh`, which also signs
`build/p3view.apk` when `debug.keystore` exists.

## Install

With USB debugging enabled on the phone:

```sh
nix run .#install    # builds, signs, then adb install -r build/p3view.apk
```

Or copy `build/p3view.apk` (from `nix run .#sign`) to the phone and open it (allow
installing unknown apps).

## Use

1. Open **P3 Thermal** and allow the Camera permission. Android only grants USB access to
   video-class devices, which the P3 reports itself as, to apps holding it.
2. Plug in the camera. Accept "Open P3 Thermal to handle Camera?" (tick "always" to skip
   the prompt next time).
3. The stream starts after about 3 seconds.

The screen shows the thermal image, the temperature at the crosshair and the fps.
Tap or drag on the image to move the crosshair. Buttons at the top right: colour scheme
(popup menu), rotate 90°, shutter (NUC), and the phone-camera overlay toggle. With the overlay
on, the rear camera shows behind the thermal image and an extra button opens the lens, zoom
and opacity controls, which apply live, plus an **Edge overlay** option that draws outlines found in
the camera view over the thermal image (with a sensitivity slider). Zoom 1× scales the camera image to the same field of
view as the thermal lens (P3 40°, P1 52° horizontal), so the zoom and shift sliders only fine-tune.
All settings are remembered (zoom and shift per lens).

The thermometer button (between rotate and shutter) opens toggles for readouts:
- **Hottest / coldest markers**: a red triangle (pointing up) on the hottest pixel and a blue one (pointing down) on the coldest, with temperatures (also
  drawn in `-annotated.png` when on).
- **Lock colour scale**: freezes the current colour range, so colours mean the same temperature
  from frame to frame and between snapshots. Not remembered across launches.
- **Colour scale bar** (on by default): under the image, `min | scale low [colour bar] scale high | max`. The scale ends are the 1st and 99th percentile the colours cover, so the real hottest and coldest pixels can lie outside them.
- **Histogram**: a percentile plot below the scale bar, white on black, 80% of the screen width. The pixels are sorted by temperature and split into one equal bucket per screen pixel; each column grows up from the bottom in proportion to its bucket's temperature between the frame minimum and maximum, so it rises left to right and flat stretches mean many pixels at the same temperature.
The marker and scale bar settings are remembered.
After an error, tap the screen to retry. The stream stops when the app leaves the
foreground and restarts when it returns.

The **?** button at the top left shows the camera model, serial number and firmware.

The camera button at the bottom right saves a snapshot to `Documents/p3view` on the phone, so the
Files app and share sheets see it. The picture button next to **?** opens that folder.
Files are named after the local time of the press, `YYYYMMDD-HHMMSS-SSS`:

- `-raw.png`: the thermal image at its native resolution in the selected colour scheme and rotation.
- `-raw.bin`: the
  temperatures behind `-raw.png`, pixel for pixel in the same orientation. No header; unsigned
  16 bit little endian, row by row, in 1/64 K (°C = value / 64 - 273.15). The size is the PNG's.
- `-annotated.png`: the same enlarged 4x, with the crosshair, its temperature and a bar below with
  the colour scale. The bar's end temperatures are what the coldest and hottest colours stand for
  (the contrast-stretch range), not the absolute frame min/max.
- `-visible.jpg`: only with the camera overlay on; the phone camera view as shown, with a red
  rectangle where the thermal image sits.

Files are rendered and written on a background thread. A number next to the button shows how
many snapshots are still waiting.

The pause button above the snapshot button freezes both the thermal image and the phone camera
view on their current frame and turns into a play button; press it again to resume. While
paused you can still move the crosshair, change the colour scheme, rotate, adjust the camera
overlay sliders, and take snapshots of the frozen frame. Pausing ends when the app goes to the
background or the camera is unplugged.

Logs: `adb logcat -s p3view`.

## How it works

```
src/dev/p3view/Protocol.kt     commands, CRC, markers, transfer joiner, frame assembler (pure Kotlin)
src/dev/p3view/Camera.kt       USB session thread: claim, commands, stream start, bulk reads, rendering
src/dev/p3view/ThermalView.kt  draws the frame scaled, rotated, palette, crosshair, text
src/dev/p3view/Palette.kt      colour schemes
src/dev/p3view/IconButton.kt   canvas-drawn round buttons
src/dev/p3view/Overlay.kt      rear camera lenses, camera2 preview session, zoomable preview view
src/dev/p3view/Snapshot.kt     snapshot queue and background writer (raw, annotated, visible)
src/dev/p3view/MainActivity.kt permissions, attach/detach, lifecycle
test/ProtocolTest.kt           JVM tests for Protocol.kt, run by build.sh
build.sh                       aapt2 -> kotlinc -> d8 -> zip -> zipalign -> apksigner
```

The USB sequence matches the Linux `p3` tool:
1. Claim interfaces 0 and 1 with `force`, which detaches the kernel UVC driver.
2. Read model, serial and firmware.
3. Send the start-stream command, then select interface 1 alt 1.
4. Send device request 0xEE.
5. Do a priming bulk read, then send the start-stream command again.
6. Stream: 8 x 64 KiB `UsbRequest`s stay queued on endpoint 0x81.

Completed requests are joined into the camera's own transfers at short packets. The
assembler handles the post-shutter frame layout and lost end markers.

Protocol sources: [jvdillon/p3-ir-camera](https://github.com/jvdillon/p3-ir-camera),
[xaionaro-go/thermalmaster](https://github.com/xaionaro-go/thermalmaster).
