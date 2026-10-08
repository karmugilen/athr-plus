# Monitoring battery use and regen availability

## Battery use

The app requests scooter snapshots every five seconds while continuously
connected (up to 720 scheduled reconnects per hour). Incoming telemetry and
manual refresh also work. The scooter's reported six-minute upload cadence is a
user observation, not a verified API guarantee; more frequent app requests do
not guarantee new vehicle readings.

An idle repository sleeps until the next scheduled refresh. Charge-limit and
remote-command deadline checks still run locally every second while needed.
Service notifications update on dashboard/limit changes; a one-minute maintenance
tick renews the cutoff wake lock and updates time-dependent notification text.
Android's notification chronometer handles the displayed countdown.

The charge-limit wake lock is retained. It prevents CPU suspend while cutoff
monitoring is required. Removing it without a replacement can delay both
snapshot checks and estimated cutoff deadlines. Android Doze also restricts
network access and idle alarms, so coroutine delays alone cannot
guarantee checks while asleep. Even the existing wake lock does not override all
Doze/network restrictions. See [Android's Doze documentation](https://developer.android.com/training/monitoring-device-state/doze-standby).

Notification deduplication and local timer scheduling reduce redundant local
work. Phone battery use at this refresh rate has not been measured. Moving more calculations to Rust does not
address socket activity or the time the CPU is kept awake. A broader power
redesign needs either explicit tolerance for delayed background checks or an
independent cutoff controller, followed by screen-off device testing.

## Regen investigation

On 2026-10-09 a read-only probe inspected the selected scooter's properties,
up to ten recent rides, and the initial WebSocket snapshot from the app's seven
existing subscribed shadow paths. Credentials and personal response values were
not printed or added to the repository. No scooter commands were sent.

The rides response included `efficiency_wh_km` and `efficiency_km_kwh`. The
subscribed telemetry included battery SoC, odometer, range, mode, GPS and charging
status. No explicit regenerative-energy, recovered-energy, battery-current or
battery-voltage fields were found in these responses.

This establishes what this account's sampled responses exposed, not that every
Ather endpoint or model lacks regen data. Ride efficiency and sparse SoC samples
cannot separate energy consumed from energy recovered. A defensible regen display
needs a documented recovered-energy counter, or sufficiently frequent signed
power/current measurements with voltage and confirmed units/sign conventions.
Until such a source is verified, the app should not display inferred regen as a
measurement. Vehicle diagnostics/CAN is a possible separate investigation; its
availability and signal definitions have not been verified here.
