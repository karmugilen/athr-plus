# Rider insights

The Insights tab contains the daily-distance planner, parked battery change,
charging history, and automatic tyre-check reminders. Charging history is also
available on the Charging tab. Records live in private app storage, separately
for each scooter. The features add no scooter API requests or background timers.

## Charging and parked observations

Charging history records the first and last battery levels seen, observed
duration, the enabled target, and physical cutoff confirmation. API acceptance
alone does not mark a cutoff confirmed. Physical stopped packets can close a
session without inventing a new battery percentage; the last observed percentage
is shown. Gaps over 30 minutes end an observation as interrupted. These records
are not a reconstruction of the scooter's earlier charging history.

Parked observations require fresh source-timestamped battery readings, a reported
parked/sleep/off/standby state, explicit noncharging state, and a stable odometer
within 0.05 km. At least two observed hours are needed for display. A gap over
30 minutes, odometer movement, charging, or a significant battery increase
restarts observation. The display describes net battery change, not a battery
health diagnosis; unobserved activity and SoC recalibration can affect it.

## Enough for tomorrow?

Enter total daily distance, including the return journey, and a reserve from
0–50%. The default is 30 km and 15% reserve. The planner needs at least three
official rides from the past 90 days with valid reported efficiency and at least
2 km distance. It uses the upper-quartile Wh/km from the latest 20 valid rides,
the model's usable battery capacity, and the chosen reserve. Local inferred trip
efficiency is excluded. The estimate remains approximate: load, terrain, wind,
traffic, temperature and battery capacity can change actual requirements.

Readiness uses a battery report no older than 12 minutes; older readings show a
refresh prompt. Plans requiring more than 100% are shown as needing a charging
stop rather than silently capped. Changing the plan never changes the charge
limit or sends a charging command.

## Automatic tyre-check learning

The rider does not enter pressures, and TPMS is not required or used by this
feature. The learned quantity is energy efficiency under approximately comparable
conditions, not tyre pressure. Without pressure measurements there is no labelled
pressure data from which to train a PSI predictor.

Eligible rides must be official cloud rides with valid Wh/km, at least 3 km,
and an average speed of 10–80 km/h. New battery/mode observations must cover the
ride with at least two samples, each endpoint within six minutes, and no sample
gap above ten minutes. All observed battery levels must be within one 20-point
band, at least 10% and below 80%, and the reported mode must be unchanged.

Comparison rides need similar route endpoints (within approximately 0.002 degrees
in each coordinate), distance within 20%, average speed within 5 km/h, and the
same observed mode and battery band. This does not control every route, load,
weather, traffic, temperature or regen difference. The below-80% restriction is a
conservative comparison filter, not a claim that regen is active at 80% on every
model. Ather's model-specific regen behavior cannot be measured with the current
cloud fields.

An alert requires at least five older baseline rides and three newer comparable
rides. All three newer rides must use at least 25% more Wh/km than the baseline
median. An excessively variable baseline (max/min over 1.4) is rejected.
Thresholds are initial heuristics, not a validated tyre-fault classifier.

The newest ride is evaluated when cloud ride history refreshes. It must be less
than 24 hours old. Notifications are deduplicated by ride and limited to one per
seven days. The rider can disable them with the Insights switch. Missing mode,
battery coverage or route data leaves the feature learning; older cloud rides
without that context cannot retroactively train it. Alerts therefore may take
time and sufficient monitored rides to become available.

The message asks the rider to check cold tyre pressure and mentions other causes
of higher energy use. It never reports predicted PSI or claims a puncture. A slow
efficiency-based heuristic cannot replace an immediate tyre safety inspection.

The observed speed–efficiency view uses comparable routes, modes and battery
bands, groups trip-average speed into 5 km/h bands, and needs at least three rides
in each of two bands. It displays the band with the lowest median Wh/km. This is
a historical association, not a recommended cruising speed or a causal optimum.

## Retention and validation

Storage is bounded to 60 charging observations, 60 parked observations, 100 cloud
rides and 7,200 battery/mode samples over at most 30 days. Account/vehicle changes
select independent saved records. Reflected persistence models retain their field
names in minified releases.

JVM tests cover observed charging endpoints, interrupted sessions, physical
confirmation, parked gaps and movement, planner inputs and impossible plans,
sustained efficiency changes, confounder exclusions, duplicate rides and learned
speed bands. An Android debug APK is built for review. Device-side notification,
overnight monitoring, and the heuristic's real-world accuracy still need field
validation.
