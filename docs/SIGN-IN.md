# Phone sign-in and session import

## Current status

Ather's login service (Cerberus) accepts OTP sign-in only from the official Ather app. Since the
official app 13.x, every request to the login routes — `/api/v2/req/9a11` (send OTP) and
`/api/v2/req/9a12` (verify OTP) — must include a Firebase App Check token minted by the official
app. That token is bound to the official app's package name, signing certificate, and Ather's
Firebase project, so a third-party client cannot produce a valid one.

Requests without a token are rejected with:

```json
{"message":"Unable to process your request. Please try again.","status":"failure","errorCode":1013}
```

and requests with a token that does not verify get `errorCode 1014`. Athr+ maps both codes to a
clear "sign-in is blocked" message on the sign-in screen instead of the generic server text.

The older login routes (`/auth/v2/generate-login-otp`, `/auth/v2/verify-login-otp`) answer every
client — including the official app — with a hard `Outdated App version.` response, so changing any
version header does not help.

## What still works

- **Existing sessions keep working.** Ather account tokens are long-lived. Telemetry, charging
  control, widgets, and history all use the session token, not the login routes.
- **Session import.** Athr+ can import a session file captured from the official Ather app on your
  own device.

## Importing a session file

The file is a small JSON document:

```json
{
  "token": "<Ather account token>",
  "vehicle_uuid": "<scooter uuid>",
  "phone": "<optional>",
  "display_name": "<optional>"
}
```

Accepted field names: `token` (required); `vehicle_uuid`, `vehicleUuid`, or `uuid`; optionally
`phone` or `contact_no`, and `display_name` or `displayName`.

Athr+ looks for `ather_session.json` in:

- `/sdcard/Android/data/io.ather.pro/files/ather_session.json` — per-app external storage. Push it
  with:

  ```sh
  adb push ather_session.json /sdcard/Android/data/io.ather.pro/files/
  ```

- the app-private files directory (on rooted devices).

The file is imported automatically the next time the app starts without a valid session, or on
demand from the sign-in screen with **"I have a session file — import it"**. After a successful
import the file is deleted and the token is stored in the app's encrypted preferences.

Obtaining the file requires access to the official app's private data (for example a rooted phone or
your own tooling). The token is a credential for your own Ather account: keep it private, and do not
share it or commit it to any repository.

## If the app reports an expired session

Ather issues a fresh token on each login in the official app. If Athr+ reports an expired session,
capture an updated `ather_session.json` from the official app and import it again.

## Tracking

[Issue #8](https://github.com/karmugilen/athr-plus/issues/8) tracks this topic, including whether the
login gate ever opens for third-party clients.
