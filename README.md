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

The screen shows the thermal image with an ironbow palette, the temperature at the centre
crosshair, frame min/max and fps. Tap the image to trigger the shutter (NUC); after an
error, tap to retry. The stream stops when the app leaves the foreground and restarts
when it returns.

Logs: `adb logcat -s p3view`.

## How it works

```
src/dev/p3view/Protocol.kt     commands, CRC, markers, transfer joiner, frame assembler (pure Kotlin)
src/dev/p3view/Camera.kt       USB session thread: claim, commands, stream start, bulk reads, rendering
src/dev/p3view/ThermalView.kt  draws the frame scaled to fit, crosshair, text
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
