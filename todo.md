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
