# p3view for Android

Minimal Android viewer for the ThermalMaster P3 (and P1) USB thermal camera.
Open the app, plug in the camera, see the thermal image. Target: Pixel 10 on Android 17
(minSdk 36, targetSdk 37).

Plain Kotlin against the platform SDK: no Gradle, no AndroidX, no native code.

## Build

```sh
nix build            # result/p3view.apk
```

The flake accepts the Android SDK license and pulls platform 37.0 and build-tools 37.0.0
through nixpkgs `androidenv`. These are unfree, so they come from Google's servers rather
than the NixOS binary cache.

Without Nix: `nix develop` gives a shell with everything set, or export `ANDROID_JAR`,
`BUILD_TOOLS` and `KOTLIN_STDLIB` yourself and run `./build.sh`.

## Install

With USB debugging enabled on the phone:

```sh
nix run .#install    # adb install -r result/p3view.apk
```

Or copy `result/p3view.apk` to the phone and open it (allow installing unknown apps).

The APK is signed with `debug.keystore` from this repo (password `android`), so every
build installs over the previous one.

## Use

1. Open **P3 Thermal** and allow the Camera permission. Android only grants USB access to
   video-class devices, which the P3 reports itself as, to apps holding it.
2. Plug in the camera. Accept "Open P3 Thermal to handle Camera?" (tick "always" to skip
   the prompt next time).
3. The stream starts after about 3 seconds.

The screen shows the thermal image, the temperature at the crosshair, frame min/max and fps.
Tap or drag on the image to move the crosshair. Buttons at the top right: colour scheme
(popup menu), rotate 90°, shutter (NUC), and the phone-camera overlay toggle. With the overlay
on, the rear camera shows behind the thermal image and an extra button opens the lens, zoom
and opacity controls, which apply live. Zoom 1× scales the camera image to the same field of
view as the thermal lens (P3 40°, P1 52° horizontal), so the zoom and shift sliders only fine-tune.
All settings are remembered (zoom and shift per lens).
After an error, tap the screen to retry. The stream stops when the app leaves the
foreground and restarts when it returns.

The **?** button at the top left shows the camera model, serial number and firmware.

The camera button at the bottom right saves a snapshot to `Pictures/p3view` on the phone, so the
gallery, the Files app and share sheets see it. The picture button next to **?** opens that folder.
Files are named after the local time of the press, `YYYYMMDD-HHMMSS-SSS`:

- `-raw.png`: the thermal image at its native resolution in the selected colour scheme and rotation.
- `-annotated.png`: the same enlarged 4x, with the crosshair, its temperature and a bar below with
  the colour scale. The bar's end temperatures are what the coldest and hottest colours stand for
  (the contrast-stretch range), not the absolute frame min/max.
- `-visible.jpg`: only with the camera overlay on; the phone camera view as shown, with a red
  rectangle where the thermal image sits.

Files are rendered and written on a background thread. A number next to the button shows how
many snapshots are still waiting.

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
