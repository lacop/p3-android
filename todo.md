# Ideas for later

Effort: S small, M medium, L large.

1. **Threshold highlight (S).** A slider that paints every pixel above (or below) a chosen
   temperature in a flat colour. For finding overheating parts or cold leaks.
2. **Region box (M).** Drag a rectangle to get its min, max and average. Extends the single
   crosshair; multiple markers would fit here too.
3. **Emissivity setting (M).** Shiny metal reads far too cool. A per-session emissivity (and
   reflected temperature) correction applied in software. Approximate, since the camera's own
   calibration is unknown.
4. **Share the last snapshot (S).** One tap to open the share sheet for the newest snapshot files.
5. **Auto parallax correction (L).** The phone's autofocus reports the subject distance. With the
   distance between the P3 and the camera lens entered once, shift the camera image by the right
   amount so alignment holds at any distance. Needs a quick experiment to see whether the Pixel
   reports focus distance accurately enough.
6. **Remember the crosshair position (S).** It resets to the centre on every launch.
7. **Visible photo at camera resolution (M).** `-visible.jpg` is a screen-sized grab. Saving it
   from the camera at full resolution would look better, but needs a still capture from the
   camera session.
8. **Pinch-zoom and pan (M).** Zoom into the thermal image (and the camera view with it) while
   paused or live. Left out of the pause feature for now.
9. **Last-snapshot thumbnail (M).** A small preview next to the snapshot button. Tapping it opens
   the image or the share sheet; it also confirms the file was saved.
10. **Patch-averaged crosshair reading (S).** A single pixel is noisy (about ±0.04 K). Average a
    3x3 or 5x5 patch around the crosshair for the number, as a setting.
11. **Low/high gain switch (S/M).** Tried and backed out. The gain commands match the documented
    P3 protocol (`012f41000000...3c3a` low, `012f41000100...4939` high, via request 0x20, and
    `Commands.gain` in `Protocol.kt` builds them), but in low gain a hand at ~33 °C read -30 °C,
    below the documented 0-550 °C range. Suspected cause: the camera needs a shutter calibration
    after switching. To try: send a shutter command ~0.5 s after the gain command; if readings
    are still off, take high and low gain snapshots (`-raw.bin`) of the same still scene with
    something hot in it and fit a correction. Only send gain once the user has chosen one, so the
    camera keeps its default until then.
