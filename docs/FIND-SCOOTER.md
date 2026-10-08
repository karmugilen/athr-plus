# Phone location and display refresh

## Live phone location

While the map is resumed, the app requests high-accuracy phone location at a one-second interval. It subscribes to the available platform fused, GPS, and network providers so an approximate network fix can appear while GPS acquires satellites. It unsubscribes when the screen pauses; this feature adds no background location permission or tracking service. Android and satellite reception determine actual update timing and accuracy. Precise location permission is needed for useful short-distance guidance.

Accepted fixes are displayed immediately using their original coordinates and reported accuracy. There is no coordinate averaging, stationary deadband, or marker interpolation. Startup can show the newest cached fix from the last five minutes while requesting a live fix. Fixes older than 15 seconds are labelled Last position with their age and stay visible through reception gaps.

The app does not apply accuracy thresholds, movement gates, jump confirmation, or smoothing. Each new Android fix is copied directly to the phone marker, including its reported accuracy when available. Invalid coordinates and callbacks older than the displayed fix are ignored. Position accuracy comes from Android and reception, not app-side processing.

The experimental Find Scooter radar card and home-screen widget have been removed. The original battery/status widget remains available.

## Display refresh rate

While the app is resumed, it requests the fastest supported display mode up to 144 Hz at the current resolution. Pausing restores the previous window preferences. Heading animations use elapsed time and display frames, including at 60, 90, 120, and 144 Hz. This is a refresh-rate request, not a guarantee of 144 rendered frames per second. Android settings, power saving, thermal limits, hardware, and WebView can constrain the result; higher active refresh rates can also use more battery.

## Validation

JVM tests cover geographic distance and bearing edge cases. The browser map suite checks heading behavior at 60/90/120/144 Hz, immediate accepted-position updates, camera gestures, following, and missing locations. Real-world positioning accuracy and sustained device frame rate still need on-device validation.
