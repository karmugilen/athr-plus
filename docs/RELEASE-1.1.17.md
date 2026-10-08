# Athr+ v1.1.17

- Adds Insights: recent charges, observed parked battery changes, and a daily-distance battery planner with reserve.
- Adds optional tyre-check reminders learned from comparable ride efficiency. These suggest an inspection; they do not measure tyre pressure or detect punctures.
- Shows phone location directly from Android without app-side averaging or movement filters. Phone updates are requested every second while the map is open; scooter positions use the latest cloud reports, with five-second snapshot checks.
- Improves compass reliability and map gestures, and requests refresh rates up to 144 Hz across the app on supported phones.
- Adds ride-route history and improves charging-time estimates from observed charging speed.
- Simplifies Charging and Insights, fixes charging text contrast, and reduces redundant background notification updates.

This is an optimized, non-debuggable release with code and resource shrinking. It uses the existing signing key so installation over the previous app preserves login, settings, and history.

On v1.1.16, open **Settings → App updates → Check now** to see this version. Review the notes and choose Download/Install when ready. Automatic update checks may take up to six hours on opening the app, or longer when Android delays background work.
